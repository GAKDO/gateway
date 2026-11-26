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
// (도구) @SpringBootTest(RANDOM_PORT) = 스프링 앱 전체를 비어 있는 아무 포트로 진짜 띄운다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 아래 TestBeans(메모리 카운터)를 함께 올린다
@Import(UpstreamRetryIntegrationTest.TestBeans.class)
// (도구) @TestMethodOrder + @Order(n) = 테스트를 정한 순서(1 → 2)대로 실행한다(1번에서 켠 가짜 서버를 2번이 이어 쓰려고)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UpstreamRetryIntegrationTest {

    // 가짜 챗봇 서버가 쓸 빈 포트를 미리 정해 둔다(처음엔 아무도 듣지 않음)
    private static final int CHAT_PORT = freePort();

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) WebTestClient = 떠 있는 gateway에 HTTP 요청을 보내고 응답을 확인하는 테스트용 클라이언트
    @Autowired
    private WebTestClient client;

    // 가짜 챗봇 서버에 요청이 몇 번 도착했는지 센다(다시 보냈는지 알아보려고)
    private static final AtomicInteger HITS = new AtomicInteger();
    // 가짜 챗봇 서버가 돌려줄 응답 코드. 테스트가 바꿀 수 있다(volatile = 다른 스레드에서도 바뀐 값이 바로 보임)
    private static volatile int status = 200;
    // 켜진 가짜 챗봇 서버를 담아 두었다가 끝날 때 끈다
    private static volatile DisposableServer chat;

    // (도구) @DynamicPropertySource = 앱이 뜨기 전에 설정 값을 코드로 넣는다
    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        // 가짜 서버를 띄우고 주소를 받는다
        String stub = StubBackend.url();
        // 챗봇을 뺀 뒤쪽 서버 5개 주소를 하나씩 돌며
        for (String name : new String[] {"AUTH_SERVER_URL", "RENTAL_SERVER_URL", "CAPTURE_SERVER_URL",
                "DIAGNOSTICS_SERVER_URL", "REPORT_SERVER_URL"}) {
            // 가짜 서버 주소로 바꾼다
            registry.add(name, () -> stub);
        }
        // 챗봇 주소는 아직 아무도 듣지 않는 포트로 보낸다
        registry.add("CHAT_SERVER_URL", () -> "http://127.0.0.1:" + CHAT_PORT);
    }

    // (도구) @AfterAll = 이 클래스의 테스트가 모두 끝난 뒤 한 번 실행된다
    @AfterAll
    static void stopChat() {
        // 가짜 챗봇 서버가 켜져 있으면
        if (chat != null) {
            // 지금 바로 끈다(다른 테스트에 포트가 남지 않게)
            chat.disposeNow();
        }
    }

    // (도구) @BeforeEach = 각 테스트가 시작되기 직전에 매번 먼저 실행된다
    @BeforeEach
    void resetHits() {
        // 도착 횟수를 0으로 되돌린다
        HITS.set(0);
    }

    // [상황] 챗봇 서버가 꺼져 있다가 0.2초 뒤 켜진다(새 컨테이너로 넘어가는 순간).
    // [기대] gateway가 다시 보내 결국 200과 보낸 본문 그대로를 받는다. 서버에는 1번만 도착한다.
    //        ※ 응답 코드: 200 = 정상 처리됨
    @Test
    @Order(1)
    void 뒤쪽_서버가_잠깐_연결을_받지_않아도_요청은_다시_보내져_본문까지_전달된다() {
        // 첫 시도는 닫힌 포트라 거절되고, 0.2초 뒤에 서버가 뜬다(새 컨테이너로 넘어가는 순간을 흉내 낸다)
        // 준비: 따로 도는 스레드를 만든다
        Thread starter = new Thread(() -> {
            // 0.2초 기다린 뒤
            sleep(200);
            // 가짜 챗봇 서버를 켠다
            chat = startChat();
        });
        // 스레드를 출발시킨다(요청을 보내는 동안 뒤에서 서버가 켜지게)
        starter.start();

        // 실행: 응답을 10초까지 기다리는 클라이언트로 바꾼다(다시 보내는 시간을 기다리려고)
        client.mutate().responseTimeout(Duration.ofSeconds(10)).build()
                // 챗봇 주소로 POST를 보낼 준비를 한다
                .post().uri("/api/v1/chat")
                // 보낼 본문을 넣는다
                .bodyValue("{\"message\":\"안녕\"}")
                // 요청을 실제로 보내고 응답을 받는다
                .exchange()
                // (도구) expectStatus()/expectBody() = 받은 응답의 상태 코드·본문을 확인한다
                // 확인: 결국 200을 받는지 확인한다
                .expectStatus().isOk()
                // 보낸 본문이 그대로 돌아왔는지 확인한다(다시 보낼 때도 본문이 빠지지 않았는지)
                .expectBody(String.class).isEqualTo("{\"message\":\"안녕\"}");
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 서버에는 딱 1번만 도착했는지 확인한다(연결 실패한 시도는 도착한 게 아님)
        assertThat(HITS.get()).isEqualTo(1);
    }

    // [상황] 켜진 챗봇 서버가 500 오류로 답한다.
    // [기대] 그 오류를 그대로 사용자에게 전하고 다시 보내지 않는다(서버 도착 1번).
    //        ※ 응답 코드: 500 = 서버 내부 오류
    @Test
    @Order(2)
    void 서버가_낸_5xx는_다시_보내지_않는다() {
        // 준비: 가짜 챗봇 서버가 500으로 답하게 바꾼다
        status = 500;

        // 실행: 챗봇 주소로 POST를 보낼 준비를 한다
        client.post().uri("/api/v1/chat")
                // 보낼 본문을 넣는다
                .bodyValue("{\"message\":\"안녕\"}")
                // 요청을 실제로 보낸다
                .exchange()
                // 확인: 서버 오류(5xx)가 그대로 전달되는지 확인한다
                .expectStatus().is5xxServerError();
        // 서버에 1번만 도착했는지 확인한다(다시 보내지 않음)
        assertThat(HITS.get()).isEqualTo(1);
    }

    /** 받은 본문을 그대로 돌려주는 가짜 chat-server. */
    // 테스트용 가짜 챗봇 서버를 켠다.
    private static DisposableServer startChat() {
        // (도구) HttpServer(Reactor Netty) = 테스트 안에서 바로 띄우는 작은 HTTP 서버
        return HttpServer.create()
                // 내 컴퓨터 안에서만 받게 한다
                .host("127.0.0.1")
                // 미리 정해 둔 챗봇 포트로 연다
                .port(CHAT_PORT)
                // 요청이 오면 본문을 끝까지 모아 글자로 읽는다
                .handle((request, response) -> request.receive().aggregate().asString(StandardCharsets.UTF_8)
                        // 본문이 없으면 빈 글자로 본다
                        .defaultIfEmpty("")
                        // 읽은 본문으로 답을 만든다
                        .flatMap(body -> {
                            // 도착 횟수를 1 늘린다
                            HITS.incrementAndGet();
                            // 정해 둔 응답 코드로
                            return response.status(status)
                                    // 받은 본문을 그대로 돌려준다
                                    .sendString(Mono.just(body), StandardCharsets.UTF_8).then();
                        }))
                // 지금 바로 서버를 띄운다
                .bindNow();
    }

    // 테스트 도구: 지금 비어 있는 포트 번호 하나를 알아낸다
    private static int freePort() {
        // 포트 0으로 소켓을 열어 운영체제가 빈 포트를 고르게 한다(끝나면 바로 닫힘)
        try (ServerSocket socket = new ServerSocket(0)) {
            // 고른 포트 번호를 돌려준다
            return socket.getLocalPort();
        } catch (IOException e) {
            // 포트를 못 열면 테스트 준비 실패로 바로 멈춘다
            throw new IllegalStateException(e);
        }
    }

    // 테스트 도구: 정한 밀리초만큼 기다린다
    private static void sleep(long millis) {
        // 가짜 업스트림이 늦게 답하는 상황을 만드는 대기(응답 지연 자체가 시험 조건)
        // parkNanos는 일찍 돌아올 수 있으므로 정한 시각까지 다시 기다린다
        // 기다림이 끝날 시각을 나노초로 계산한다
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        // 남은 시간을 계산한다
        long remaining = deadline - System.nanoTime();
        // 남은 시간이 있는 동안
        while (remaining > 0) {
            // 남은 만큼 쉰다
            java.util.concurrent.locks.LockSupport.parkNanos(remaining);
            // 일찍 깼을 수 있으니 남은 시간을 다시 계산한다
            remaining = deadline - System.nanoTime();
        }
    }

    // (도구) @TestConfiguration = 테스트에서만 쓰는 스프링 객체(빈)를 만드는 설정 클래스
    @TestConfiguration
    static class TestBeans {

        // (도구) @Bean = 돌려준 객체를 스프링이 관리하게 한다. @Primary = 같은 종류가 여럿이면 이것을 먼저 쓴다
        @Bean
        @Primary
        InMemoryRateCounter inMemoryRateCounter() {
            // Redis 카운터 대신 메모리 카운터를 넣는다(Docker 없이 돌게)
            return new InMemoryRateCounter();
        }
    }
}
