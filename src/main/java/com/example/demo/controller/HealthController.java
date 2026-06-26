package com.example.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check for uptime monitors. Deliberately touches no database, so pings do not keep it awake. */
@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public String health() {
        return "ok";
    }
}
