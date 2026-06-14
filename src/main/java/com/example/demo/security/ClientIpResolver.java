package com.example.demo.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP used as a rate-limit key.
 * X-Forwarded-For is client-controlled, so it is only read when the app runs behind
 * {@code app.rate-limit.trusted-proxies} reverse proxies that each append the address they saw.
 */
@Component
public class ClientIpResolver {

    @Value("${app.rate-limit.trusted-proxies:0}")
    private int trustedProxies;

    public String resolve(HttpServletRequest request) {
        if (trustedProxies > 0) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] hops = forwardedFor.split(",");
                int clientIndex = hops.length - trustedProxies;
                if (clientIndex >= 0) {
                    return hops[clientIndex].trim();
                }
            }
        }
        return request.getRemoteAddr();
    }
}
