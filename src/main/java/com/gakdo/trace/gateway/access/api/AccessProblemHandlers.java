package com.gakdo.trace.gateway.access.api;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;

import com.gakdo.trace.gateway.access.domain.AccessDecision;
import com.gakdo.trace.gateway.common.ProblemWriter;

import reactor.core.publisher.Mono;

/** 401·403·404를 Problem Details로 쓴다. 토큰 없이 계약에 없는 경로를 부르면 진입점으로 오므로 404도 여기서 가른다. */
public class AccessProblemHandlers implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ProblemWriter writer;

    public AccessProblemHandlers(ProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        AccessDecision decision = PolicyAuthorizationManager.decisionOf(exchange, AccessDecision.UNAUTHENTICATED);
        if (decision != AccessDecision.NOT_FOUND) {
            decision = AccessDecision.UNAUTHENTICATED;
        }
        exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
        return write(exchange, decision);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return write(exchange, PolicyAuthorizationManager.decisionOf(exchange, AccessDecision.FORBIDDEN));
    }

    private Mono<Void> write(ServerWebExchange exchange, AccessDecision decision) {
        return writer.write(exchange, decision.status(), decision.code(), decision.title());
    }
}
