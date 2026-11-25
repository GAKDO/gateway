// 이 파일이 확인하는 것: 서버를 새 버전으로 바꿔 끼우는 순간(무중단 배포)의 gateway 동작.
// - 뒤쪽 서버가 잠깐 연결을 안 받으면 gateway가 다시 보내 사용자는 오류를 보지 않는다.
// - 하지만 서버가 실제로 처리하다 낸 오류(5xx)는 다시 보내지 않는다(같은 일을 두 번 하지 않게).
// 챗봇 주소(/api/v1/chat)를 처음엔 닫힌 포트로 보내 놓고 시험한다.

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

    // [상황] 챗봇 서버가 꺼져 있다가 0.2초 뒤 켜진다(새 컨테이너로 넘어가는 순간).
    // [기대] gateway가 다시 보내 결국 200과 보낸 본문 그대로를 받는다. 서버에는 1번만 도착한다.
    //        ※ 응답 코드: 200 = 정상 처리됨
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

    // [상황] 켜진 챗봇 서버가 500 오류로 답한다.
    // [기대] 그 오류를 그대로 사용자에게 전하고 다시 보내지 않는다(서버 도착 1번).
    //        ※ 응답 코드: 500 = 서버 내부 오류
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
    // 테스트용 가짜 챗봇 서버를 켠다.
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
        // 가짜 업스트림이 늦게 답하는 상황을 만드는 대기(응답 지연 자체가 시험 조건)
        // parkNanos는 일찍 돌아올 수 있으므로 정한 시각까지 다시 기다린다
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        long remaining = deadline - System.nanoTime();
        while (remaining > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(remaining);
            remaining = deadline - System.nanoTime();
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
