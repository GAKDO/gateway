// 이 파일이 확인하는 것: gateway 점검 주소(/api/v1/gateway/ping)가 돌려주는 내용.

package com.gakdo.trace.gateway.ping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

// 컨트롤러 슬라이스라 보안은 뺀다. ping이 인증 없이 열리는지는 TokenVerificationIntegrationTest가 본다
@WebFluxTest(controllers = PingController.class, properties = "app.commit=test-sha",
        excludeAutoConfiguration = {ReactiveSecurityAutoConfiguration.class,
                ReactiveOAuth2ResourceServerAutoConfiguration.class})
class PingControllerTest {

    @Autowired
    private WebTestClient client;

    // [상황] 점검 주소를 부른다.
    // [기대] 서비스 이름 gateway, 상태 ok, 배포된 커밋 값을 돌려준다(어떤 버전이 떠 있는지 확인).
    @Test
    void ping() {
        client.get().uri("/api/v1/gateway/ping").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("gateway")
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.commit").isEqualTo("test-sha");
    }
}
