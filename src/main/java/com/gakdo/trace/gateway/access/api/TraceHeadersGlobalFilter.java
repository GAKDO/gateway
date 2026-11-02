package com.gakdo.trace.gateway.access.api;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;

import com.gakdo.trace.gateway.access.domain.TokenPrincipal;

import reactor.core.publisher.Mono;

/**
 * 서버에 넘기는 신원 헤더(X-User-Id·X-Role·X-Scope)를 토큰 값으로만 채운다.
 * 클라이언트가 같은 이름을 보내도 지워서, 서버가 헤더를 믿어도 위조되지 않게 한다.
 */
public class TraceHeadersGlobalFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String ROLE = "X-Role";
    public static final String SCOPE = "X-Scope";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(JwtPrincipals::from)
                .map(principal -> withHeaders(exchange, principal))
                .defaultIfEmpty(withHeaders(exchange, null))
                .flatMap(chain::filter);
    }

    static ServerWebExchange withHeaders(ServerWebExchange exchange, TokenPrincipal principal) {
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(ROLE);
            headers.remove(SCOPE);
            if (principal != null) {
                setIfPresent(headers, USER_ID, principal.subject());
                setIfPresent(headers, ROLE, principal.rawRole());
                setIfPresent(headers, SCOPE, principal.scope());
            }
        }).build();
        return exchange.mutate().request(request).build();
    }

    private static void setIfPresent(org.springframework.http.HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
