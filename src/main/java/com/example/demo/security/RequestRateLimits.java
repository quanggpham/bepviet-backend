package com.example.demo.security;

import com.example.demo.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Rate-limit policy for endpoints that are abused cheaply: credential guessing and paid LLM calls.
 */
@Component
@RequiredArgsConstructor
public class RequestRateLimits {

    private static final Duration AUTH_WINDOW = Duration.ofMinutes(15);
    private static final Duration AI_IP_WINDOW = Duration.ofMinutes(1);
    private static final Duration AI_BUDGET_WINDOW = Duration.ofDays(1);
    private static final String TOO_MANY = "Bạn thao tác quá nhiều lần, vui lòng thử lại sau";

    private final RateLimiter rateLimiter;

    @Value("${app.rate-limit.login.max-failures:5}")
    private int loginMaxFailures;

    @Value("${app.rate-limit.login.per-ip:50}")
    private int loginPerIp;

    @Value("${app.rate-limit.forgot-password.per-email:3}")
    private int forgotPerEmail;

    @Value("${app.rate-limit.forgot-password.per-ip:10}")
    private int forgotPerIp;

    @Value("${app.rate-limit.reset-password.per-ip:20}")
    private int resetPerIp;

    @Value("${app.rate-limit.ai.per-ip-per-minute:10}")
    private int aiPerIpPerMinute;

    @Value("${app.rate-limit.ai.daily-budget:1000}")
    private int aiDailyBudget;

    @Value("${app.rate-limit.ai.max-message-length:500}")
    private int aiMaxMessageLength;

    /**
     * Failures are counted per (IP, e-mail): an attacker elsewhere cannot lock the real user out,
     * while the per-IP cap stops one client from spraying many accounts.
     */
    public void checkLogin(String ip, String email) {
        rateLimiter.ensureBelow(loginFailureKey(ip, email), loginMaxFailures, AUTH_WINDOW,
                "Bạn đã đăng nhập sai quá nhiều lần, vui lòng thử lại sau");
        rateLimiter.acquire("login-ip:" + ip, loginPerIp, AUTH_WINDOW, TOO_MANY);
    }

    public void loginFailed(String ip, String email) {
        rateLimiter.record(loginFailureKey(ip, email));
    }

    public void loginSucceeded(String ip, String email) {
        rateLimiter.reset(loginFailureKey(ip, email));
    }

    public void checkForgotPassword(String ip, String email) {
        rateLimiter.acquire("forgot-ip:" + ip, forgotPerIp, AUTH_WINDOW, TOO_MANY);
        rateLimiter.acquire("forgot-email:" + normalize(email), forgotPerEmail, AUTH_WINDOW, TOO_MANY);
    }

    public void checkResetPassword(String ip) {
        rateLimiter.acquire("reset-ip:" + ip, resetPerIp, AUTH_WINDOW, TOO_MANY);
    }

    /** Per-IP first, so one noisy client is refused before it can drain the shared daily budget. */
    public void checkAiChat(String ip, String message) {
        if (message == null || message.isBlank() || message.length() > aiMaxMessageLength) {
            throw new BusinessException("Câu hỏi phải có từ 1 đến " + aiMaxMessageLength + " ký tự");
        }
        rateLimiter.acquire("ai-ip:" + ip, aiPerIpPerMinute, AI_IP_WINDOW, TOO_MANY);
        rateLimiter.acquire("ai-global", aiDailyBudget, AI_BUDGET_WINDOW,
                "Trợ lý AI đã hết lượt trong hôm nay, vui lòng quay lại sau");
    }

    private static String loginFailureKey(String ip, String email) {
        return "login-fail:" + ip + "|" + normalize(email);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
