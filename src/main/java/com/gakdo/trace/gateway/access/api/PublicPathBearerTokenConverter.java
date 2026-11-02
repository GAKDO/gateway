package com.gakdo.trace.gateway.access.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;

import com.gakdo.trace.gateway.access.domain.AccessPolicy;

import reactor.core.publisher.Mono;

/**
 * 공개 경로에서는 Bearer 토큰을 읽지 않는다.
 * 웹이 만료된 토큰을 붙인 채 로그인·검증을 불러도 401이 나지 않게 한다.
 */
public class PublicPathBearerTokenConverter implements ServerAuthenticationConverter {

    private final ServerBearerTokenAuthenticationConverter delegate = new ServerBearerTokenAuthenticationConverter();
    private final AccessPolicy policy;

    public PublicPathBearerTokenConverter(AccessPolicy policy) {
        this.policy = policy;
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        boolean publicPath = policy.match(method, path).map(m -> m.rule().publicAccess()).orElse(false);
        return publicPath ? Mono.empty() : delegate.convert(exchange);
    }
}
