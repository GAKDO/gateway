// 이 파일이 확인하는 것: 뒤쪽 서버가 끝까지 꺼져 있을 때 gateway가 주는 오류 모양.

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
// (도구) @SpringBootTest(RANDOM_PORT) = 스프링 앱 전체를 비어 있는 아무 포트로 진짜 띄운다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 아래 TestBeans(메모리 카운터)를 함께 올린다
@Import(UpstreamUnavailableProblemIntegrationTest.TestBeans.class)
class UpstreamUnavailableProblemIntegrationTest {

    // 아무도 듣지 않는 포트 번호를 하나 정해 둔다(챗봇 서버가 꺼진 상황을 만들려고)
    private static final int CLOSED_PORT = freePort();

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) WebTestClient = 떠 있는 gateway에 HTTP 요청을 보내고 응답을 확인하는 테스트용 클라이언트
    @Autowired
    private WebTestClient client;

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
        // 챗봇 주소만 닫힌 포트로 보낸다(연결이 늘 실패하게)
        registry.add("CHAT_SERVER_URL", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    // [상황] 챗봇 서버가 계속 꺼져 있어 재시도 6번이 모두 실패한다.
    // [기대] 503과 계약 오류 형식(Problem JSON, code INTERNAL_ERROR, 어느 주소였는지)으로 답한다.
    //        웹 화면이 이 형식을 읽어 "잠시 후 다시" 안내를 띄울 수 있다.
    //        ※ 응답 코드: 503 = 서버를 지금 쓸 수 없음(점검·장애)
    @Test
    void 뒤쪽_서버가_내려가_있으면_503과_Problem_INTERNAL_ERROR로_답한다() {
        // 실행: 응답을 15초까지 기다리게 바꾼 클라이언트를 만든다(재시도 6번에 시간이 걸려서)
        client.mutate().responseTimeout(Duration.ofSeconds(15)).build()
                // 챗봇 주소로 POST를 보낼 준비를 한다
                .post().uri("/api/v1/chat")
                // 본문이 JSON이라고 알린다
                .contentType(MediaType.APPLICATION_JSON)
                // 보낼 질문 본문을 넣는다
                .bodyValue("{\"message\":\"안녕\"}")
                // 요청을 실제로 보내고 응답을 받는다
                .exchange()
                // (도구) expectStatus()/expectHeader()/expectBody() = 받은 응답의 상태 코드·헤더·본문을 확인한다
                // 확인: 503(지금 쓸 수 없음)인지 확인한다
                .expectStatus().isEqualTo(503)
                // 응답이 표준 오류 형식(problem+json)인지 확인한다
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                // 이제부터 응답 본문(JSON)을 확인한다
                .expectBody()
                // (도구) jsonPath("$.status") = 응답 JSON에서 status 칸을 가리킨다. isEqualTo(...)로 값이 맞는지 본다
                // 본문의 status도 503인지 확인한다
                .jsonPath("$.status").isEqualTo(503)
                // 오류 코드가 계약에 있는 INTERNAL_ERROR인지 확인한다
                .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
                // 어느 주소에서 난 오류인지(instance) 확인한다
                .jsonPath("$.instance").isEqualTo("/api/v1/chat");
    }

    // 테스트 도구: 지금 비어 있는 포트 번호 하나를 알아낸다
    private static int freePort() {
        // 포트 0으로 소켓을 열어 운영체제가 빈 포트를 고르게 한다(try 괄호 덕에 끝나면 바로 닫힘)
        try (ServerSocket socket = new ServerSocket(0)) {
            // 고른 포트 번호를 돌려준다(소켓이 닫히면 아무도 듣지 않는 포트가 됨)
            return socket.getLocalPort();
        } catch (IOException e) {
            // 포트를 못 열면 테스트 준비 실패로 바로 멈춘다
            throw new IllegalStateException(e);
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
