package com.gakdo.trace.gateway.ping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(PingController.class)
class PingControllerTest {

    @Autowired
    private WebTestClient client;

    @Test
    void ping() {
        client.get().uri("/api/v1/gateway/ping").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("gateway")
                .jsonPath("$.status").isEqualTo("ok");
    }
}
