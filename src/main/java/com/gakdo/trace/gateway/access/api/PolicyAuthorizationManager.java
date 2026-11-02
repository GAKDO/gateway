package com.gakdo.trace.gateway.access.api;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.web.server.ServerWebExchange;

import com.gakdo.trace.gateway.access.domain.AccessDecision;
import com.gakdo.trace.gateway.access.domain.AccessPolicy;

import reactor.core.publisher.Mono;

/**
 * 스프링 보안의 인가 단계에서 AccessPolicy를 부른다.
 * 거부 사유(401·403 FORBIDDEN·403 OUT_OF_SCOPE·404)는 교환 속성에 남겨 진입점·거부 처리기가 같은 code로 답하게 한다.
 */
public class PolicyAuthorizationManager implements ReactiveAuthorizationManager<AuthorizationContext> {

    public static final String DECISION_ATTRIBUTE = PolicyAuthorizationManager.class.getName() + ".decision";

    private final AccessPolicy policy;

    public PolicyAuthorizationManager(AccessPolicy policy) {
        this.policy = policy;
    }

    @Override
    public Mono<AuthorizationDecision> check(Mono<Authentication> authentication, AuthorizationContext context) {
        ServerWebExchange exchange = context.getExchange();
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        return authentication
                .map(JwtPrincipals::from)
                .map(principal -> policy.decide(method, path, principal))
                .switchIfEmpty(Mono.fromSupplier(() -> policy.decide(method, path, null)))
                .map(decision -> {
                    exchange.getAttributes().put(DECISION_ATTRIBUTE, decision);
                    return new AuthorizationDecision(decision.allowed());
                });
    }

    /** 거부 사유. 인가 단계 전에 실패했으면(깨진 토큰 등) fallback. */
    public static AccessDecision decisionOf(ServerWebExchange exchange, AccessDecision fallback) {
        Object value = exchange.getAttribute(DECISION_ATTRIBUTE);
        return value instanceof AccessDecision decision && !decision.allowed() ? decision : fallback;
    }
}
