// 이 파일이 확인하는 것: 요구사항 FR-AUTH-03 무차별 시도 막기. gateway를 실제로 띄워
// 로그인·페어링 코드·가입·소셜 로그인·비밀번호 재설정을 같은 IP에서 연달아 보내 본다.

package com.gakdo.trace.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.gakdo.trace.gateway.support.GatewayIntegrationTest;

/**
 * FR-AUTH-03: 같은 IP에서 로그인·코드 넣기를 연달아 하면 잠시 막는다(비밀번호·코드 맞히기, 메일 폭탄 방지).
 * gateway를 실제로 띄우고 뒤쪽 서버는 가짜(StubBackend), 횟수는 메모리 카운터(InMemoryRateCounter)로 센다.
 * 한도를 넘으면 429 RATE_LIMITED와 Retry-After(몇 초 뒤 다시) 헤더를 준다.
 */
class RateLimitIntegrationTest extends GatewayIntegrationTest {

    // 한도 값은 application.yml의 IpRateLimit 설정(이름, 횟수, 초)과 같다. 설정을 바꾸면 여기도 맞춘다.
    /** 로그인·페어링 코드·가입·인증 코드·재발송·비밀번호 재설정: 같은 IP에서 1분에 10번까지(각 기능 따로 셈). */
    private static final int AUTH_LIMIT_PER_MIN = 10;
    /** 소셜 로그인 시작·콜백: 같은 IP에서 모든 제공자를 합쳐 1분에 30번까지. */
    private static final int OAUTH_LIMIT_PER_MIN = 30;

    // [상황] 같은 IP에서 1분 안에 로그인을 11번 시도한다(비밀번호 맞히기 공격을 흉내).
    // [기대] 10번까지는 통과, 11번째는 429 RATE_LIMITED와 "몇 초 뒤 다시"(Retry-After).
    //        횟수는 "trace:gw:rl:login:" 이름으로 센다.
    //        ※ 응답 코드: 429 = 요청이 너무 잦아 잠시 막음
    @Test
    void 같은_IP에서_로그인을_연속_실패하면_제한된다() {
        for (int i = 0; i < AUTH_LIMIT_PER_MIN; i++) {
            client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
        }
        client.post().uri("/api/v1/auth/login").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.code").isEqualTo("RATE_LIMITED");
        assertThat(rateCounter.counts().keySet()).isNotEmpty()
                .allMatch(key -> key.startsWith("trace:gw:rl:login:"));
    }

    // [상황] 진단 에이전트의 6자리 페어링 코드를 같은 IP에서 11번 넣어 본다(코드 맞히기 공격).
    // [기대] 11번째는 429로 막는다.
    //        ※ 응답 코드: 429 = 요청이 너무 잦아 잠시 막음
    @Test
    void 페어링_코드를_10회_넘게_넣으면_제한된다() {
        for (int i = 0; i < AUTH_LIMIT_PER_MIN; i++) {
            client.post().uri("/api/v1/auth/pairing-codes/exchange").exchange().expectStatus().isOk();
        }
        client.post().uri("/api/v1/auth/pairing-codes/exchange").exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
    }

    // [상황] 로그인으로 한도를 다 쓴 뒤 같은 IP에서 회원가입·페어링 코드를 부른다.
    // [기대] 제한은 기능마다 따로라 가입·페어링은 그대로 된다.
    @Test
    void 로그인_제한은_다른_auth_경로에_번지지_않는다() {
        for (int i = 0; i < AUTH_LIMIT_PER_MIN + 1; i++) { // 한도를 넘겨 로그인 제한을 건다
            client.post().uri("/api/v1/auth/login").exchange();
        }
        client.post().uri("/api/v1/auth/signup").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/pairing-codes/exchange").exchange().expectStatus().isOk();
    }

    // [상황] 회원가입, 이메일 인증 코드 확인, 인증 메일 재발송을 각각 같은 IP에서 11번씩 보낸다.
    // [기대] 각각 11번째에 429(메일 폭탄·코드 맞히기 방지).
    //        ※ 응답 코드: 429 = 요청이 너무 잦아 잠시 막음
    @Test
    void 가입_인증코드_확인_재발송도_같은_IP에서_분당_10회를_넘으면_제한된다() {
        for (String path : new String[] {"/api/v1/auth/signup", "/api/v1/auth/email/verify", "/api/v1/auth/email/resend"}) {
            for (int i = 0; i < AUTH_LIMIT_PER_MIN; i++) {
                client.post().uri(path).exchange().expectStatus().isOk();
            }
            client.post().uri(path).exchange()
                    .expectStatus().isEqualTo(429)
                    .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        }
    }

    // [상황] 구글 로그인 시작과 깃허브 로그인 콜백을 15번씩(합쳐 30번) 보내고 한 번 더 보낸다.
    // [기대] 둘을 합쳐 30번이 한도라 31번째는 429. 소셜 로그인 버튼 목록 조회는 제한과 무관하게 된다.
    //        ※ 응답 코드: 429 = 요청이 너무 잦아 잠시 막음
    @Test
    void 소셜_로그인_시작과_콜백은_같은_IP에서_합쳐_분당_30회를_넘으면_제한된다() {
        // 한 바퀴에 구글 시작·깃허브 콜백 두 번을 보내므로 30 ÷ 2 = 15바퀴면 합쳐서 한도(30번)를 딱 채운다.
        // 두 경로가 한 통으로 세어지는지 보려고 일부러 섞어 보낸다.
        for (int i = 0; i < OAUTH_LIMIT_PER_MIN / 2; i++) {
            client.get().uri("/api/v1/auth/oauth/google/start").exchange().expectStatus().isOk();
            client.get().uri("/api/v1/auth/oauth/github/callback").exchange().expectStatus().isOk();
        }
        client.get().uri("/api/v1/auth/oauth/google/start").exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        client.get().uri("/api/v1/auth/oauth/providers").exchange().expectStatus().isOk();
    }

    // [상황] 비밀번호 재설정 메일 요청과 재설정 실행을 각각 같은 IP에서 11번씩 보낸다.
    // [기대] 각각 11번째에 429.
    //        ※ 응답 코드: 429 = 요청이 너무 잦아 잠시 막음
    @Test
    void 비밀번호_재설정_요청과_재설정도_같은_IP에서_분당_10회를_넘으면_제한된다() {
        for (String path : new String[] {"/api/v1/auth/password/reset-request", "/api/v1/auth/password/reset"}) {
            for (int i = 0; i < AUTH_LIMIT_PER_MIN; i++) {
                client.post().uri(path).exchange().expectStatus().isOk();
            }
            client.post().uri(path).exchange()
                    .expectStatus().isEqualTo(429)
                    .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        }
    }
}
