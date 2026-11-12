package com.gakdo.trace.gateway.upstream;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.gakdo.trace.gateway.support.InMemoryRateCounter;
import com.gakdo.trace.gateway.support.StubBackend;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무중단 교체 때의 동작: 뒤쪽 서버에 연결하지 못하면 다시 보내고, 서버가 낸 오류는 다시 보내지 않는다.
 * 챗봇 라우트(/api/v1/chat, 로그인 없음)를 닫힌 포트로 보내 놓고 시험한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(UpstreamRetryIntegrationTest.TestBeans.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UpstreamRetryIntegrationTest {

    private static final int CHAT_PORT = freePort();

    @Autowired
    private WebTestClient client;

    private static final AtomicInteger HITS = new AtomicInteger();
    private static volatile int status = 200;
    private static volatile DisposableServer chat;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        String stub = StubBackend.url();
        for (String name : new String[] {"AUTH_SERVER_URL", "RENTAL_SERVER_URL", "CAPTURE_SERVER_URL",
                "DIAGNOSTICS_SERVER_URL", "REPORT_SERVER_URL"}) {
            registry.add(name, () -> stub);
        }
        registry.add("CHAT_SERVER_URL", () -> "http://127.0.0.1:" + CHAT_PORT);
    }

    @AfterAll
    static void stopChat() {
        if (chat != null) {
            chat.disposeNow();
        }
    }

    @BeforeEach
    void resetHits() {
        HITS.set(0);
    }

    @Test
    @Order(1)
    void 뒤쪽_서버가_잠깐_연결을_받지_않아도_요청은_다시_보내져_본문까지_전달된다() {
        // 첫 시도는 닫힌 포트라 거절되고, 0.2초 뒤에 서버가 뜬다(새 컨테이너로 넘어가는 순간을 흉내 낸다)
        Thread starter = new Thread(() -> {
            sleep(200);
            chat = startChat();
        });
        starter.start();

        client.mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/v1/chat")
                .bodyValue("{\"message\":\"안녕\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"message\":\"안녕\"}");
        assertThat(HITS.get()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void 서버가_낸_5xx는_다시_보내지_않는다() {
        status = 500;

        client.post().uri("/api/v1/chat")
                .bodyValue("{\"message\":\"안녕\"}")
                .exchange()
                .expectStatus().is5xxServerError();
        assertThat(HITS.get()).isEqualTo(1);
    }

    /** 받은 본문을 그대로 돌려주는 가짜 chat-server. */
    private static DisposableServer startChat() {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(CHAT_PORT)
                .handle((request, response) -> request.receive().aggregate().asString(StandardCharsets.UTF_8)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            HITS.incrementAndGet();
                            return response.status(status)
                                    .sendString(Mono.just(body), StandardCharsets.UTF_8).then();
                        }))
                .bindNow();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
