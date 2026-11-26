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

    // 떠 있는 가짜 서버를 한 번만 만들어 모든 테스트가 같이 쓰려고 담아 둔다
    private static DisposableServer server;

    // 객체를 만들 일이 없는 도구 모음이라 생성자를 막아 둔다
    private StubBackend() {
    }

    // 테스트 도구: 가짜 서버를 (처음이면) 띄우고 주소를 돌려준다. synchronized = 여러 테스트가 동시에 불러도 한 번만 띄우게 한다
    public static synchronized String url() {
        // 아직 안 띄웠을 때만 새로 만든다
        if (server == null) {
            // (도구) HttpServer(Reactor Netty) = 테스트 안에서 바로 띄우는 작은 HTTP 서버
            server = HttpServer.create()
                    // 내 컴퓨터 안에서만 받게 한다
                    .host("127.0.0.1")
                    // 비어 있는 포트를 아무거나 쓰게 한다(포트 충돌 방지)
                    .port(0)
                    // 요청이 오면 아래 규칙대로 답한다
                    .handle((request, response) -> {
                        // 공개키(JWKS) 주소를 부르면
                        if (request.uri().startsWith("/.well-known/jwks.json")) {
                            // 테스트용 공개키 JSON을 돌려준다(gateway가 토큰 서명을 확인할 수 있게)
                            return response.header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                    .sendString(Mono.just(TestTokens.jwksJson()), StandardCharsets.UTF_8);
                        }
                        // 나머지 주소는 받은 요청 정보를 JSON으로 그대로 돌려준다(gateway가 넘긴 헤더를 테스트가 볼 수 있게)
                        return response.header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                .sendString(Mono.just(echo(request)), StandardCharsets.UTF_8);
                    })
                    // 지금 바로 서버를 띄운다
                    .bindNow();
        }
        // 떠 있는 가짜 서버의 주소를 돌려준다
        return "http://127.0.0.1:" + server.port();
    }

    // 테스트 도구: 받은 요청의 방식·경로·신원 헤더·토큰 유무를 JSON 글자로 만든다
    private static String echo(HttpServerRequest request) {
        // 방식·경로·X-User-Id·X-Role·X-Scope와 Authorization 헤더가 있었는지를 한 줄 JSON으로 이어 붙인다
        return "{\"backend\":\"stub\",\"method\":\"" + request.method().name() + "\""
                + ",\"path\":\"" + request.fullPath() + "\""
                + ",\"xUserId\":\"" + header(request, "X-User-Id") + "\""
                + ",\"xRole\":\"" + header(request, "X-Role") + "\""
                + ",\"xScope\":\"" + header(request, "X-Scope") + "\""
                + ",\"hasAuthorization\":" + request.requestHeaders().contains("Authorization") + "}";
    }

    // 테스트 도구: 헤더 값을 꺼낸다
    private static String header(HttpServerRequest request, String name) {
        // 이름으로 헤더 값을 읽는다
        String value = request.requestHeaders().get(name);
        // 없으면 빈 글자, 있으면 따옴표를 빼고 돌려준다(JSON이 깨지지 않게)
        return value == null ? "" : value.replace("\"", "");
    }
}
