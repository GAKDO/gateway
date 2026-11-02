package com.gakdo.trace.gateway.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.gakdo.trace.gateway.support.GatewayIntegrationTest;
import com.gakdo.trace.gateway.support.TestTokens;

/** FR-AUTH-03: JWT 서명·만료·발급자 검증과 서버로 넘기는 신원 헤더. */
class TokenVerificationIntegrationTest extends GatewayIntegrationTest {

    private static final String PROTECTED = "/api/v1/devices";
    private static final String PUBLIC = "/api/v1/auth/login";

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

    @Test
    void 만료된_토큰은_401() {
        bearer(PROTECTED, TestTokens.expiredAdmin()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 다른_키로_서명한_토큰은_401() {
        bearer(PROTECTED, TestTokens.signedByOtherKey()).expectStatus().isUnauthorized();
    }

    @Test
    void 발급자가_trace가_아닌_토큰은_401() {
        bearer(PROTECTED, TestTokens.wrongIssuerAdmin()).expectStatus().isUnauthorized();
    }

    @Test
    void 형식이_깨진_토큰은_401() {
        bearer(PROTECTED, "not-a-jwt").expectStatus().isUnauthorized();
    }

    @Test
    void 모르는_역할의_토큰은_403_FORBIDDEN() {
        bearer(PROTECTED, TestTokens.withRole("ROOT")).expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void 공개_경로는_만료된_토큰을_붙여도_통과한다() {
        client.post().uri(PUBLIC).header("Authorization", "Bearer " + TestTokens.expiredAdmin()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.backend").isEqualTo("stub");
    }

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

    @Test
    void 위조한_역할_헤더는_토큰_값으로_덮어쓴다() {
        client.get().uri("/api/v1/auth/me")
                .header("Authorization", "Bearer " + TestTokens.customer())
                .header("X-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.xRole").isEqualTo("CUSTOMER");
    }

    @Test
    void 계약에_없는_경로는_404_NOT_FOUND() {
        client.get().uri("/api/v1/internal/secret").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        bearer("/api/v1/rentals/x/y/z", TestTokens.admin()).expectStatus().isNotFound();
    }

    @Test
    void 계약에_없는_메서드는_404() {
        client.delete().uri("/api/v1/devices").header("Authorization", "Bearer " + TestTokens.admin()).exchange()
                .expectStatus().isNotFound();
    }

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
