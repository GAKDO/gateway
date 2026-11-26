// 이 파일이 확인하는 것: CORS(다른 웹사이트에서 TRACE API를 부르는 것) 제한.
// TRACE 웹사이트 주소에서 온 요청만 브라우저가 쓸 수 있게 허락하고, 다른 사이트는 막는다.

package com.gakdo.trace.gateway.access;

import org.junit.jupiter.api.Test;

import com.gakdo.trace.gateway.support.GatewayIntegrationTest;

/** CORS는 web 주소만 허용한다. */
// GatewayIntegrationTest를 이어받아 gateway를 실제로 띄우고 가짜 서버를 뒤에 붙인 상태에서 시험한다
class CorsIntegrationTest extends GatewayIntegrationTest {

    // [상황] TRACE 웹에서 로그인 요청을 보내기 전, 브라우저가 "보내도 되나요?"(사전 요청)를 묻는다.
    // [기대] 허락(200)하고, 로그인 쿠키 같은 자격 증명도 함께 보내도 된다고 답한다.
    //        ※ 응답 코드: 200 = 정상 처리됨
    @Test
    void web_출처의_사전_요청은_허용하고_자격_증명을_받는다() {
        // (도구) client = WebTestClient. HTTP 요청을 보내고 응답을 줄줄이 확인하는 테스트용 클라이언트
        // 실행: 로그인 주소에 OPTIONS(사전 요청)를 보낸다
        client.options().uri("/api/v1/auth/login")
                // 요청이 TRACE 웹사이트에서 왔다고 알린다
                .header("Origin", WEB_ORIGIN)
                // "POST로 보내도 되나요?"라고 묻는다
                .header("Access-Control-Request-Method", "POST")
                // "Content-Type 헤더를 붙여도 되나요?"라고 묻는다
                .header("Access-Control-Request-Headers", "Content-Type")
                // 요청을 실제로 보내고 응답을 받는다
                .exchange()
                // (도구) expectStatus()/expectHeader() = 받은 응답의 상태 코드·헤더를 확인한다
                // 확인: 200(허락)인지 확인한다
                .expectStatus().isOk()
                // 허락한 출처가 TRACE 웹 주소인지 확인한다
                .expectHeader().valueEquals("Access-Control-Allow-Origin", WEB_ORIGIN)
                // 쿠키 같은 자격 증명도 함께 보내도 된다고 답하는지 확인한다
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
    }

    // [상황] 수상한 다른 사이트(evil.example)에서 같은 사전 요청을 보낸다.
    // [기대] 403으로 거절하고 허락 헤더를 붙이지 않는다(브라우저가 요청을 막음).
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    void 다른_출처의_사전_요청은_403이고_허용_헤더가_없다() {
        // 실행: 로그인 주소에 사전 요청을 보낸다
        client.options().uri("/api/v1/auth/login")
                // 요청이 수상한 다른 사이트에서 왔다고 알린다
                .header("Origin", "https://evil.example")
                // "POST로 보내도 되나요?"라고 묻는다
                .header("Access-Control-Request-Method", "POST")
                // 요청을 실제로 보낸다
                .exchange()
                // 확인: 403(거절)인지 확인한다
                .expectStatus().isForbidden()
                // 허락 헤더가 아예 없는지 확인한다(브라우저가 막게)
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    // [상황] TRACE 웹에서 실제 로그인 요청을 보낸다.
    // [기대] 응답에도 허락 헤더가 붙어 브라우저가 답을 읽을 수 있다.
    @Test
    void web_출처의_실제_요청에도_허용_헤더가_붙는다() {
        // 실행: TRACE 웹에서 로그인 주소로 실제 POST를 보낸다
        client.post().uri("/api/v1/auth/login")
                // 요청이 TRACE 웹사이트에서 왔다고 알린다
                .header("Origin", WEB_ORIGIN)
                // 요청을 실제로 보낸다
                .exchange()
                // 확인: 200(성공)인지 확인한다
                .expectStatus().isOk()
                // 응답에도 허락 헤더가 붙어 브라우저가 답을 읽을 수 있는지 확인한다
                .expectHeader().valueEquals("Access-Control-Allow-Origin", WEB_ORIGIN);
    }
}
