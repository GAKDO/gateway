package com.gakdo.trace.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.gakdo.trace.gateway.support.GatewayIntegrationTest;

/** FR-AUTH-03: 같은 IP에서 로그인을 연속 실패하면 제한된다. */
class RateLimitIntegrationTest extends GatewayIntegrationTest {

    @Test
    void 같은_IP에서_로그인을_연속_실패하면_제한된다() {
        for (int i = 0; i < 10; i++) {
            client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
        }
        client.post().uri("/api/v1/auth/login").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.code").isEqualTo("RATE_LIMITED");
        assertThat(rateCounter.counts().keySet()).allMatch(key -> key.startsWith("trace:gw:rl:login:"));
    }

    @Test
    void 페어링_코드를_10회_넘게_넣으면_제한된다() {
        for (int i = 0; i < 10; i++) {
            client.post().uri("/api/v1/auth/pairing-codes/exchange").exchange().expectStatus().isOk();
        }
        client.post().uri("/api/v1/auth/pairing-codes/exchange").exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
    }

    @Test
    void 로그인_제한은_다른_auth_경로에_번지지_않는다() {
        for (int i = 0; i < 11; i++) {
            client.post().uri("/api/v1/auth/login").exchange();
        }
        client.post().uri("/api/v1/auth/signup").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/pairing-codes/exchange").exchange().expectStatus().isOk();
    }

    @Test
    void 가입_인증코드_확인_재발송도_같은_IP에서_분당_10회를_넘으면_제한된다() {
        for (String path : new String[] {"/api/v1/auth/signup", "/api/v1/auth/email/verify", "/api/v1/auth/email/resend"}) {
            for (int i = 0; i < 10; i++) {
                client.post().uri(path).exchange().expectStatus().isOk();
            }
            client.post().uri(path).exchange()
                    .expectStatus().isEqualTo(429)
                    .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        }
    }

    @Test
    void 비밀번호_재설정_요청과_재설정도_같은_IP에서_분당_10회를_넘으면_제한된다() {
        for (String path : new String[] {"/api/v1/auth/password/reset-request", "/api/v1/auth/password/reset"}) {
            for (int i = 0; i < 10; i++) {
                client.post().uri(path).exchange().expectStatus().isOk();
            }
            client.post().uri(path).exchange()
                    .expectStatus().isEqualTo(429)
                    .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        }
    }
}
