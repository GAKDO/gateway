package com.gakdo.trace.gateway.ping;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
public class PingController {

    @GetMapping("/api/v1/gateway/ping")
    public Mono<Map<String, String>> ping() {
        return Mono.just(Map.of("service", "gateway", "status", "ok"));
    }
}
