// 테스트 공통 바탕(테스트 자체는 아님): gateway를 실제로 띄우고, 뒤쪽 서버 6개(인증·렌탈·촬영·
// 진단·리포트·챗봇)는 가짜 서버(StubBackend) 하나로 바꾼다. Redis 대신 메모리 카운터를 쓰고,
// 매 테스트 전에 횟수를 0으로 되돌린다. 시계를 고정해 1분 창 경계에 걸려 결과가 흔들리지 않게 한다.

package com.gakdo.trace.gateway.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
 * 요청 제한 시계는 고정해 한 테스트의 요청이 60초 창 경계를 넘지 않게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayIntegrationTest.TestBeans.class)
public abstract class GatewayIntegrationTest {

    // 테스트에서 "TRACE 웹사이트 주소"로 쓰는 값(CORS 허용 대상).
    public static final String WEB_ORIGIN = "https://web.trace.test";

    @Autowired
    protected WebTestClient client;

    @Autowired
    protected InMemoryRateCounter rateCounter;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        String url = StubBackend.url();
        for (String name : new String[] {"AUTH_SERVER_URL", "RENTAL_SERVER_URL", "CAPTURE_SERVER_URL",
                "DIAGNOSTICS_SERVER_URL", "REPORT_SERVER_URL", "CHAT_SERVER_URL"}) {
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

        @Bean
        Clock rateLimitClock() {
            return Clock.fixed(Instant.parse("2026-11-08T00:00:30Z"), ZoneOffset.UTC);
        }
    }
}
