package com.gakdo.trace.gateway.upstream;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.gakdo.trace.gateway.support.InMemoryRateCounter;
import com.gakdo.trace.gateway.support.StubBackend;

/**
 * 뒤쪽 서버가 끝내 연결을 받지 않으면(재시도 6번 뒤) gateway도 계약의 오류 형식(Problem + code)으로 답한다.
 * 챗봇 라우트(/api/v1/chat, 로그인 없음)를 늘 닫힌 포트로 보내 놓고 시험한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(UpstreamUnavailableProblemIntegrationTest.TestBeans.class)
class UpstreamUnavailableProblemIntegrationTest {

    private static final int CLOSED_PORT = freePort();

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        String stub = StubBackend.url();
        for (String name : new String[] {"AUTH_SERVER_URL", "RENTAL_SERVER_URL", "CAPTURE_SERVER_URL",
                "DIAGNOSTICS_SERVER_URL", "REPORT_SERVER_URL"}) {
            registry.add(name, () -> stub);
        }
        registry.add("CHAT_SERVER_URL", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    @Test
    void 뒤쪽_서버가_내려가_있으면_503과_Problem_INTERNAL_ERROR로_답한다() {
        client.mutate().responseTimeout(Duration.ofSeconds(15)).build()
                .post().uri("/api/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"안녕\"}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.instance").isEqualTo("/api/v1/chat");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
