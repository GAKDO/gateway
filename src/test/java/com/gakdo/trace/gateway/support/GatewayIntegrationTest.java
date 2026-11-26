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
// (도구) @SpringBootTest(RANDOM_PORT) = 스프링 앱 전체를 비어 있는 아무 포트로 진짜 띄운다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 아래 TestBeans(메모리 카운터·고정 시계)를 함께 올린다
@Import(GatewayIntegrationTest.TestBeans.class)
// abstract = 혼자 실행되지 않고, 통합 테스트들이 이어받아 쓰는 바탕 클래스
public abstract class GatewayIntegrationTest {

    // 테스트에서 "TRACE 웹사이트 주소"로 쓰는 값(CORS 허용 대상).
    public static final String WEB_ORIGIN = "https://web.trace.test";

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) WebTestClient = 떠 있는 gateway에 HTTP 요청을 보내고 응답을 확인하는 테스트용 클라이언트
    @Autowired
    protected WebTestClient client;

    // 횟수를 지우거나 들여다보려고 메모리 카운터를 받는다
    @Autowired
    protected InMemoryRateCounter rateCounter;

    // (도구) @DynamicPropertySource = 앱이 뜨기 전에 설정 값을 코드로 넣는다(가짜 서버 주소처럼 실행해 봐야 아는 값)
    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        // 가짜 서버를 띄우고 주소를 받는다
        String url = StubBackend.url();
        // 뒤쪽 서버 6개의 주소 설정을 하나씩 돌며
        for (String name : new String[] {"AUTH_SERVER_URL", "RENTAL_SERVER_URL", "CAPTURE_SERVER_URL",
                "DIAGNOSTICS_SERVER_URL", "REPORT_SERVER_URL", "CHAT_SERVER_URL"}) {
            // 모두 가짜 서버 주소로 바꾼다(진짜 서버 없이 gateway만 시험하려고)
            registry.add(name, () -> url);
        }
        // CORS 허용 주소를 테스트용 웹 주소로 정한다
        registry.add("TRACE_WEB_ORIGIN", () -> WEB_ORIGIN);
    }

    // (도구) @BeforeEach = 각 테스트가 시작되기 직전에 매번 먼저 실행된다
    @BeforeEach
    void resetRateCounter() {
        // 요청 횟수를 0으로 되돌린다(앞 테스트 때문에 요청 제한에 걸리지 않게)
        rateCounter.reset();
    }

    // (도구) @TestConfiguration = 테스트에서만 쓰는 스프링 객체(빈)를 만드는 설정 클래스
    @TestConfiguration
    static class TestBeans {

        // (도구) @Bean = 이 메서드가 돌려준 객체를 스프링이 관리하게 한다. @Primary = 같은 종류가 여럿이면 이것을 먼저 쓴다
        @Bean
        @Primary
        InMemoryRateCounter inMemoryRateCounter() {
            // Redis 카운터 대신 메모리 카운터를 넣는다(Docker 없이 돌게)
            return new InMemoryRateCounter();
        }

        // 요청 제한이 쓰는 시계를 바꿔 끼운다
        @Bean
        Clock rateLimitClock() {
            // 시각을 0분 30초로 고정한다(테스트 중 1분 창 경계를 넘어 횟수가 갑자기 0이 되지 않게)
            return Clock.fixed(Instant.parse("2026-11-08T00:00:30Z"), ZoneOffset.UTC);
        }
    }
}
