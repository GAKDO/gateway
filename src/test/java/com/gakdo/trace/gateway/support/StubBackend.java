// 테스트 도구(테스트 자체는 아님): 진짜 TRACE 서버들 대신 쓰는 가짜 서버.
// - 인증 서버의 공개키(JWKS) 주소를 부르면 테스트용 공개키를 준다(토큰 서명 확인용).
// - 다른 주소는 200과 함께 "gateway가 넘겨준 사용자 ID·역할·범위 헤더"를 그대로 돌려준다.
// 그래서 테스트는 "gateway가 통과시켰는지, 어떤 신원을 넘겼는지"만 보면 된다.
// ※ 응답 코드: 200 = 정상 처리됨

package com.gakdo.trace.gateway.support;

import java.nio.charset.StandardCharsets;

import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;

/**
 * 서버 5개 대신 쓰는 가짜 서버. JWKS를 내주고, 나머지 요청은 200과 받은 신원 헤더를 JSON으로 돌려준다.
 * 실제 서버 없이 "gateway가 통과시켰는지"와 "어떤 헤더를 넘겼는지"를 본다.
 */
// ※ 응답 코드: 200 = 정상 처리됨
public final class StubBackend {

    private static DisposableServer server;

    private StubBackend() {
    }

    public static synchronized String url() {
        if (server == null) {
            server = HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle((request, response) -> {
                        if (request.uri().startsWith("/.well-known/jwks.json")) {
                            return response.header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                    .sendString(Mono.just(TestTokens.jwksJson()), StandardCharsets.UTF_8);
                        }
                        return response.header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                .sendString(Mono.just(echo(request)), StandardCharsets.UTF_8);
                    })
                    .bindNow();
        }
        return "http://127.0.0.1:" + server.port();
    }

    private static String echo(HttpServerRequest request) {
        return "{\"backend\":\"stub\",\"method\":\"" + request.method().name() + "\""
                + ",\"path\":\"" + request.fullPath() + "\""
                + ",\"xUserId\":\"" + header(request, "X-User-Id") + "\""
                + ",\"xRole\":\"" + header(request, "X-Role") + "\""
                + ",\"xScope\":\"" + header(request, "X-Scope") + "\""
                + ",\"hasAuthorization\":" + request.requestHeaders().contains("Authorization") + "}";
    }

    private static String header(HttpServerRequest request, String name) {
        String value = request.requestHeaders().get(name);
        return value == null ? "" : value.replace("\"", "");
    }
}
