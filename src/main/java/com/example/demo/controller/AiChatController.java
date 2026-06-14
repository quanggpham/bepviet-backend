package com.example.demo.controller;

import com.example.demo.dto.response.ApiResponse;
import com.example.demo.security.ClientIpResolver;
import com.example.demo.security.RequestRateLimits;
import com.example.demo.service.FoodAdvisorService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final FoodAdvisorService foodAdvisorService;
    private final VectorStore vectorStore;
    private final RequestRateLimits rateLimits;
    private final ClientIpResolver clientIpResolver;


    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam String message,
            HttpServletRequest httpRequest) {
        rateLimits.checkAiChat(clientIpResolver.resolve(httpRequest), message);
        log.info("Streaming chat request: {}", message);
        return foodAdvisorService.advise(message);
    }

    @GetMapping("/chat")
    public ResponseEntity<ApiResponse<String>> chat(
            @RequestParam(defaultValue = "Quán có món gì ngon?") String message,
            HttpServletRequest httpRequest) {
        rateLimits.checkAiChat(clientIpResolver.resolve(httpRequest), message);
        log.info("Chat request: {}", message);

        String response = foodAdvisorService.advise(message)
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block();

        return ResponseEntity.ok(
                ApiResponse.<String>builder()
                        .data(response)
                        .status(HttpStatus.OK.value())
                        .message("Chat thành công")
                        .build()
        );
    }
}