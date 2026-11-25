// 이 파일이 확인하는 것: CORS(다른 웹사이트에서 TRACE API를 부르는 것) 제한.
// TRACE 웹사이트 주소에서 온 요청만 브라우저가 쓸 수 있게 허락하고, 다른 사이트는 막는다.

package com.gakdo.trace.gateway.access;

import org.junit.jupiter.api.Test;

import com.gakdo.trace.gateway.support.GatewayIntegrationTest;

/** CORS는 web 주소만 허용한다. */
class CorsIntegrationTest extends GatewayIntegrationTest {

    // [상황] TRACE 웹에서 로그인 요청을 보내기 전, 브라우저가 "보내도 되나요?"(사전 요청)를 묻는다.
    // [기대] 허락(200)하고, 로그인 쿠키 같은 자격 증명도 함께 보내도 된다고 답한다.
    //        ※ 응답 코드: 200 = 정상 처리됨
    @Test
    void web_출처의_사전_요청은_허용하고_자격_증명을_받는다() {
        client.options().uri("/api/v1/auth/login")
                .header("Origin", WEB_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", WEB_ORIGIN)
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
    }

    // [상황] 수상한 다른 사이트(evil.example)에서 같은 사전 요청을 보낸다.
    // [기대] 403으로 거절하고 허락 헤더를 붙이지 않는다(브라우저가 요청을 막음).
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    void 다른_출처의_사전_요청은_403이고_허용_헤더가_없다() {
        client.options().uri("/api/v1/auth/login")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    // [상황] TRACE 웹에서 실제 로그인 요청을 보낸다.
    // [기대] 응답에도 허락 헤더가 붙어 브라우저가 답을 읽을 수 있다.
    @Test
    void web_출처의_실제_요청에도_허용_헤더가_붙는다() {
        client.post().uri("/api/v1/auth/login")
                .header("Origin", WEB_ORIGIN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", WEB_ORIGIN);
    }
}
