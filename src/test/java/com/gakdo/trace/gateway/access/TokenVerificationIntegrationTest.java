// 이 파일이 확인하는 것: 요구사항 FR-AUTH-03 로그인 토큰(JWT) 검사.
// - 토큰이 없거나, 만료됐거나, 다른 열쇠로 서명했거나, 발급자가 TRACE가 아니면 막는지(401).
// - 통과한 요청에는 "누가 보냈는지"(사용자 ID·역할·범위) 헤더를 붙여 뒤쪽 서버로 넘기는지.
// - 사용자가 직접 "나는 관리자" 헤더를 꾸며 보내도 속지 않는지.

package com.gakdo.trace.gateway.access;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.gakdo.trace.gateway.support.GatewayIntegrationTest;
import com.gakdo.trace.gateway.support.TestTokens;

/** FR-AUTH-03: JWT 서명·만료·발급자 검증과 서버로 넘기는 신원 헤더. */
class TokenVerificationIntegrationTest extends GatewayIntegrationTest {

    private static final String PROTECTED = "/api/v1/devices";
    private static final String PUBLIC = "/api/v1/auth/login";

    // [상황] 로그인 없이 관리자용 기기 목록을 부른다.
    // [기대] 401 UNAUTHENTICATED를 계약 오류 형식(Problem JSON)으로 답하고, "Bearer 토큰이 필요"하다는 헤더를 준다.
    @Test
    void 토큰_없이_보호된_경로를_부르면_401_UNAUTHENTICATED_Problem() {
        client.get().uri(PROTECTED).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.instance").isEqualTo(PROTECTED);
    }

    // [상황] 유효 시간이 지난 관리자 토큰으로 부른다.
    // [기대] 401로 막는다(다시 로그인해야 함).
    @Test
    void 만료된_토큰은_401() {
        bearer(PROTECTED, TestTokens.expiredAdmin()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    // [상황] TRACE 인증 서버가 아닌 다른 열쇠로 서명한 가짜 관리자 토큰으로 부른다.
    // [기대] 401로 막는다(위조 토큰 차단).
    @Test
    void 다른_키로_서명한_토큰은_401() {
        bearer(PROTECTED, TestTokens.signedByOtherKey()).expectStatus().isUnauthorized();
    }

    // [상황] 발급자가 trace가 아닌 토큰으로 부른다.
    // [기대] 401로 막는다.
    @Test
    void 발급자가_trace가_아닌_토큰은_401() {
        bearer(PROTECTED, TestTokens.wrongIssuerAdmin()).expectStatus().isUnauthorized();
    }

    // [상황] JWT 모양이 아닌 아무 글자를 토큰으로 보낸다.
    // [기대] 401로 막는다.
    @Test
    void 형식이_깨진_토큰은_401() {
        bearer(PROTECTED, "not-a-jwt").expectStatus().isUnauthorized();
    }

    // [상황] 서명은 맞지만 TRACE에 없는 역할(ROOT)이 적힌 토큰으로 부른다.
    // [기대] 403 FORBIDDEN.
    @Test
    void 모르는_역할의_토큰은_403_FORBIDDEN() {
        bearer(PROTECTED, TestTokens.withRole("ROOT")).expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    // [상황] 로그인 화면에서 예전(만료된) 토큰이 붙은 채로 로그인 요청을 보낸다.
    // [기대] 로그인은 공개 주소라 토큰과 상관없이 통과한다(만료 때문에 다시 로그인을 못 하는 일이 없게).
    @Test
    void 공개_경로는_만료된_토큰을_붙여도_통과한다() {
        client.post().uri(PUBLIC).header("Authorization", "Bearer " + TestTokens.expiredAdmin()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.backend").isEqualTo("stub");
    }

    // [상황] 사이트 도우미 챗봇을 로그인 없이 부르고, 만료된 토큰을 붙여서도 부른다.
    // [기대] 둘 다 통과한다. 토큰은 검사하지 않고 그대로 챗봇에 넘기며(챗봇이 알아서 판단),
    //        역할 헤더는 붙이지 않는다. 챗봇 ping도 열린다.
    @Test
    void 챗봇은_로그인_없이_열리고_붙인_토큰은_검사하지_않고_그대로_넘긴다() {
        client.post().uri("/api/v1/chat").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/chat");
        client.post().uri("/api/v1/chat").header("Authorization", "Bearer " + TestTokens.expiredAdmin()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.hasAuthorization").isEqualTo(true)
                .jsonPath("$.xRole").isEqualTo("");
        client.get().uri("/api/v1/chat/ping").exchange().expectStatus().isOk();
    }

    // [상황] QR을 찍은 폰(촬영 JWT)이 촬영 세트 상태를 조회한다.
    // [기대] 뒤쪽 서버로 넘길 때 토큰에서 읽은 사용자 ID·역할(CUSTOMER)·범위(시점 번호)를 헤더로 붙이고,
    //        원래 토큰도 함께 넘긴다.
    @Test
    void 허용된_요청은_토큰_값으로_신원_헤더를_붙여_넘긴다() {
        bearer("/api/v1/captures/sets/sEt0000000000000000aa", TestTokens.capture())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.xUserId").isEqualTo("customer-1")
                .jsonPath("$.xRole").isEqualTo("CUSTOMER")
                .jsonPath("$.xScope").isEqualTo(TestTokens.CHECKPOINT)
                .jsonPath("$.hasAuthorization").isEqualTo(true);
    }

    // [상황] 로그인 없이 "나는 admin-1, 역할 ADMIN"이라는 헤더를 직접 꾸며 보낸다.
    // [기대] gateway가 그 헤더를 지워서 뒤쪽 서버에는 빈 값으로 간다(관리자 사칭 차단).
    @Test
    void 클라이언트가_보낸_신원_헤더는_지운다() {
        client.post().uri(PUBLIC)
                .header("X-User-Id", "admin-1").header("X-Role", "ADMIN").header("X-Scope", "anything")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.xUserId").isEqualTo("")
                .jsonPath("$.xRole").isEqualTo("")
                .jsonPath("$.xScope").isEqualTo("");
    }

    // [상황] 고객 토큰을 붙이고 역할 헤더만 ADMIN으로 꾸며 보낸다.
    // [기대] 토큰에 적힌 진짜 역할(CUSTOMER)로 덮어써서 넘긴다.
    @Test
    void 위조한_역할_헤더는_토큰_값으로_덮어쓴다() {
        client.get().uri("/api/v1/auth/me")
                .header("Authorization", "Bearer " + TestTokens.customer())
                .header("X-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.xRole").isEqualTo("CUSTOMER");
    }

    // [상황] 계약에 없는 내부 주소나 이상한 깊은 주소를 부른다(관리자 토큰을 붙여도).
    // [기대] 404 NOT_FOUND. 계약에 적힌 주소만 밖으로 연다.
    @Test
    void 계약에_없는_경로는_404_NOT_FOUND() {
        client.get().uri("/api/v1/internal/secret").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        bearer("/api/v1/rentals/x/y/z", TestTokens.admin()).expectStatus().isNotFound();
    }

    // [상황] 계약에 없는 주소를 깨진 토큰과 함께 부른다.
    // [기대] 401이 아니라 404. (주소가 있는지 없는지를 토큰 오류로 짐작하지 못하게)
    @Test
    void 계약에_없는_경로는_깨진_토큰이_붙어도_401이_아니라_404() {
        client.get().uri("/api/v1/internal/secret").header("Authorization", "Bearer not-a-jwt").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    // [상황] 브라우저 사전 요청 모양이 아닌 OPTIONS 방식으로 보호된 주소를 부른다.
    // [기대] 권한 검사를 건너뛰는 틈이 되지 않고 404.
    @Test
    void 사전_요청이_아닌_OPTIONS는_인가를_건너뛰지_못하고_404() {
        client.options().uri("/api/v1/devices").exchange()
                .expectStatus().isNotFound();
    }

    // [상황] 관리자 토큰으로 기기 목록 주소를 DELETE 방식으로 부른다(계약에 없는 동작).
    // [기대] 404.
    @Test
    void 계약에_없는_메서드는_404() {
        client.delete().uri("/api/v1/devices").header("Authorization", "Bearer " + TestTokens.admin()).exchange()
                .expectStatus().isNotFound();
    }

    // [상황] 운영 점검 주소(gateway ping)와 쿠버네티스 살아 있음 확인 주소를 로그인 없이 부른다.
    // [기대] 둘 다 열려 있다.
    @Test
    void gateway_ping과_헬스_체크는_인증_없이_열린다() {
        client.get().uri("/api/v1/gateway/ping").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("gateway");
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec bearer(String path, String token) {
        return client.get().uri(path).header("Authorization", "Bearer " + token).exchange();
    }
}
