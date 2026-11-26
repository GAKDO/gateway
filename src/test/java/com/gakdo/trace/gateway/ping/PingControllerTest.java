// 이 파일이 확인하는 것: gateway 점검 주소(/api/v1/gateway/ping)가 돌려주는 내용.

package com.gakdo.trace.gateway.ping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * gateway의 ping(점검) 주소 시험. 배포 뒤 "서버가 떴는지, 어떤 버전·커밋이 돌고 있는지" 확인할 때 부르는 주소다.
 * 컨트롤러만 띄우는 가벼운 시험(DB·메시지 큐 없음)이며, 커밋 값은 test-sha로 고정해 응답에 그대로 나오는지 본다.
 */
// 컨트롤러 슬라이스라 보안은 뺀다. ping이 인증 없이 열리는지는 TokenVerificationIntegrationTest가 본다
// (도구) @WebFluxTest = 서버 전체가 아니라 지정한 컨트롤러만 띄워 빠르게 시험한다. properties로 커밋 값을 test-sha로 고정하고, 보안 설정은 뺀다
@WebFluxTest(controllers = PingController.class, properties = "app.commit=test-sha",
        excludeAutoConfiguration = {ReactiveSecurityAutoConfiguration.class,
                ReactiveOAuth2ResourceServerAutoConfiguration.class})
class PingControllerTest {

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) WebTestClient = 서버에 HTTP 요청을 보내고 응답을 줄줄이 확인하는 테스트용 클라이언트
    @Autowired
    private WebTestClient client;

    // [상황] 점검 주소를 부른다.
    // [기대] 서비스 이름 gateway, 상태 ok, 배포된 커밋 값을 돌려준다(어떤 버전이 떠 있는지 확인).
    @Test
    void ping() {
        // 실행: 점검 주소에 GET 요청을 보낸다(exchange = 실제로 보내고 응답을 받음)
        client.get().uri("/api/v1/gateway/ping").exchange()
                // (도구) expectStatus()/expectBody() = 받은 응답의 상태 코드·본문을 확인한다. 다르면 테스트가 실패한다
                // 확인: 200(성공)인지 확인한다
                .expectStatus().isOk()
                // 이제부터 응답 본문(JSON)을 확인한다
                .expectBody()
                // (도구) jsonPath("$.service") = 응답 JSON에서 service 칸을 가리킨다. isEqualTo(...)로 값이 맞는지 본다
                // 서비스 이름이 gateway인지 확인한다
                .jsonPath("$.service").isEqualTo("gateway")
                // 상태가 ok인지 확인한다
                .jsonPath("$.status").isEqualTo("ok")
                // 고정해 둔 커밋 값이 그대로 나오는지 확인한다(어떤 버전이 떠 있는지 알 수 있게)
                .jsonPath("$.commit").isEqualTo("test-sha");
    }
}
