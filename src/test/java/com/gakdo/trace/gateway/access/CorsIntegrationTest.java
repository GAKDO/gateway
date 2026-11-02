package com.gakdo.trace.gateway.access;

import org.junit.jupiter.api.Test;

import com.gakdo.trace.gateway.support.GatewayIntegrationTest;

/** CORS는 web 주소만 허용한다. */
class CorsIntegrationTest extends GatewayIntegrationTest {

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

    @Test
    void 다른_출처의_사전_요청은_403이고_허용_헤더가_없다() {
        client.options().uri("/api/v1/auth/login")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void web_출처의_실제_요청에도_허용_헤더가_붙는다() {
        client.post().uri("/api/v1/auth/login")
                .header("Origin", WEB_ORIGIN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", WEB_ORIGIN);
    }
}
