package com.gakdo.trace.gateway.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * gateway 전체(보안 체인 + 라우트)를 실제 포트로 띄우고, 서버 5개와 auth-server JWKS는 StubBackend로 바꾼다.
 * Redis 대신 InMemoryRateCounter를 쓴다(Docker 없는 세션에서도 돈다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayIntegrationTest.TestBeans.class)
public abstract class GatewayIntegrationTest {

    public static final String WEB_ORIGIN = "https://web.trace.test";

    @Autowired
    protected WebTestClient client;

    @Autowired
    protected InMemoryRateCounter rateCounter;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        String url = StubBackend.url();
        for (String name : new String[] {"AUTH_SERVER_URL", "RENTAL_SERVER_URL", "CAPTURE_SERVER_URL",
                "DIAGNOSTICS_SERVER_URL", "REPORT_SERVER_URL"}) {
            registry.add(name, () -> url);
        }
        registry.add("TRACE_WEB_ORIGIN", () -> WEB_ORIGIN);
    }

    @BeforeEach
    void resetRateCounter() {
        rateCounter.reset();
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        InMemoryRateCounter inMemoryRateCounter() {
            return new InMemoryRateCounter();
        }
    }
}
