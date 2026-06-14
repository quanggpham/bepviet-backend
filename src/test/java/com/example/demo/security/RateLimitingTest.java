package com.example.demo.security;

import com.example.demo.repository.PasswordResetTokenRepository;
import com.example.demo.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every test uses its own client IP and e-mail so limiter state never leaks between tests.
 */
@TestPropertySource(properties = "app.rate-limit.ai.daily-budget=30")
class RateLimitingTest extends IntegrationTestBase {

    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    RateLimiter rateLimiter;

    @BeforeEach
    void resetLimiter() {
        // tests share one application context; the global AI budget must not leak between them
        rateLimiter.clearAll();
    }

    // --- login ---

    @Test
    void loginIsBlockedAfterFiveFailedAttemptsEvenWithCorrectPassword() throws Exception {
        String ip = randomIp();
        String email = register();

        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            codes.add(login(ip, email, "wrong-" + i).getStatus());
        }
        MockHttpServletResponse correct = login(ip, email, PASSWORD);
        System.out.printf("[RATELIMIT] login: wrongAttempts=6 codes=%s thenCorrectPassword=%d retryAfter=%s%n",
                codes, correct.getStatus(), correct.getHeader("Retry-After"));

        assertThat(codes.subList(0, 5)).containsOnly(401);
        assertThat(codes.get(5)).isEqualTo(429);
        assertThat(correct.getStatus()).isEqualTo(429);
        assertThat(correct.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void failedAttemptsFromAnotherIpDoNotLockOutTheRealUser() throws Exception {
        String email = register();
        String attackerIp = randomIp();
        for (int i = 0; i < 6; i++) {
            login(attackerIp, email, "wrong-" + i);
        }
        assertThat(login(randomIp(), email, PASSWORD).getStatus()).isEqualTo(200);
    }

    @Test
    void successfulLoginsAreNotThrottled() throws Exception {
        String ip = randomIp();
        String email = register();
        for (int i = 0; i < 10; i++) {
            assertThat(login(ip, email, PASSWORD).getStatus()).isEqualTo(200);
        }
    }

    // --- forgot / reset password ---

    @Test
    void forgotPasswordDoesNotRevealWhetherEmailExists() throws Exception {
        String ip = randomIp();
        int unknown = forgot(ip, "nobody-" + UUID.randomUUID() + "@test.vn").getStatus();
        int known = forgot(ip, register()).getStatus();
        System.out.printf("[RATELIMIT] forgot-enumeration: unknownEmail=%d knownEmail=%d%n", unknown, known);

        assertThat(unknown).isEqualTo(known).isEqualTo(200);
    }

    @Test
    void forgotPasswordIsLimitedPerEmail() throws Exception {
        String email = register();
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            codes.add(forgot(randomIp(), email).getStatus());
        }
        System.out.printf("[RATELIMIT] forgot-per-email: requests=4 codes=%s%n", codes);

        assertThat(codes.subList(0, 3)).containsOnly(200);
        assertThat(codes.get(3)).isEqualTo(429);
    }

    @Test
    void resetCodeIsInvalidatedAfterFiveWrongGuesses() throws Exception {
        String email = register();
        forgot(randomIp(), email);
        String realCode = passwordResetTokenRepository.findByEmail(email).orElseThrow().getToken();

        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            codes.add(reset(randomIp(), email, wrongCode(realCode, i)).getStatus());
        }
        int withRealCode = reset(randomIp(), email, realCode).getStatus();
        System.out.printf("[RATELIMIT] reset-sequential: wrongGuesses=5 codes=%s thenRealCode=%d%n", codes, withRealCode);

        assertThat(withRealCode).isNotEqualTo(200);
        assertThat(login(randomIp(), email, "Passw0rd!2").getStatus()).isEqualTo(401);
    }

    @RepeatedTest(10)
    void parallelGuessesCannotExceedTheAttemptBudget() throws Exception {
        String email = register();
        forgot(randomIp(), email);
        String realCode = passwordResetTokenRepository.findByEmail(email).orElseThrow().getToken();

        List<Callable<Integer>> guesses = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String guess = wrongCode(realCode, i);
            String ip = randomIp();
            guesses.add(() -> reset(ip, email, guess).getStatus());
        }
        List<Integer> codes = runConcurrently(guesses);
        long evaluated = codes.stream().filter(c -> c == 400).count();
        int withRealCode = reset(randomIp(), email, realCode).getStatus();
        System.out.printf("[RATELIMIT] reset-parallel: guesses=50 evaluated(400)=%d rejected(429)=%d thenRealCode=%d%n",
                evaluated, codes.stream().filter(c -> c == 429).count(), withRealCode);

        assertThat(evaluated).isLessThanOrEqualTo(5);
        assertThat(withRealCode).isNotEqualTo(200);
    }

    // --- AI chatbot ---

    @Test
    void chatRejectsOversizedMessages() throws Exception {
        int status = mockMvc.perform(withIp(get("/api/v1/ai/chat").param("message", "a".repeat(2_000)), randomIp()))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    @Test
    void chatIsLimitedPerIp() throws Exception {
        String ip = randomIp();
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            codes.add(chat(ip).getStatus());
        }
        System.out.printf("[RATELIMIT] ai-per-ip: requests=12 codes=%s%n", codes);

        assertThat(codes.subList(0, 10)).containsOnly(200);
        assertThat(codes.subList(10, 12)).containsOnly(429);
    }

    @Test
    void streamingChatIsLimitedPerIpToo() throws Exception {
        // the per-IP budget is shared; use it up on /chat, then the stream endpoint must refuse
        String ip = randomIp();
        for (int i = 0; i < 10; i++) {
            chat(ip);
        }
        int status = mockMvc.perform(withIp(get("/api/v1/ai/chat/stream").param("message", "mon cay")
                        .accept(MediaType.TEXT_EVENT_STREAM), ip))
                .andReturn().getResponse().getStatus();
        System.out.printf("[RATELIMIT] ai-stream: after10ChatRequests streamStatus=%d%n", status);

        assertThat(status).isEqualTo(429);
    }

    @Test
    void chatHasAGlobalDailyBudget() throws Exception {
        // budget is 30 in this test class; each IP stays under its own per-minute limit
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            codes.add(chat(randomIp()).getStatus());
        }
        long ok = codes.stream().filter(c -> c == 200).count();
        System.out.printf("[RATELIMIT] ai-global: budget=30 requests=40 ok=%d rejected=%d%n",
                ok, codes.stream().filter(c -> c == 429).count());

        assertThat(ok).isLessThanOrEqualTo(30);
        assertThat(codes.get(39)).isEqualTo(429);
    }

    // --- helpers ---

    private String register() throws Exception {
        String email = "rl-" + UUID.randomUUID().toString().substring(0, 8) + "@test.vn";
        String phone = uniquePhone();
        int status = mockMvc.perform(withIp(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"RL","email":"%s","phone":"%s","password":"%s"}
                                """.formatted(email, phone, PASSWORD)), randomIp()))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(201);
        return email;
    }

    private MockHttpServletResponse login(String ip, String email, String password) throws Exception {
        return mockMvc.perform(withIp(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)), ip))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse forgot(String ip, String email) throws Exception {
        return mockMvc.perform(withIp(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)), ip))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse reset(String ip, String email, String code) throws Exception {
        return mockMvc.perform(withIp(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"Passw0rd!2\"}".formatted(email, code)), ip))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse chat(String ip) throws Exception {
        return mockMvc.perform(withIp(get("/api/v1/ai/chat").param("message", "mon cay"), ip))
                .andReturn().getResponse();
    }

    private MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }

    /** A 6-digit code guaranteed to differ from the real one. */
    private String wrongCode(String realCode, int i) {
        String guess;
        int n = i;
        do {
            guess = String.format("%06d", (Integer.parseInt(realCode) + 1 + n++) % 1_000_000);
        } while (guess.equals(realCode));
        return guess;
    }

    private List<Integer> runConcurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            }));
        }
        ready.await();
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return codes;
    }
}
