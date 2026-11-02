package com.gakdo.trace.gateway.access.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 역할 × 경로 판단. 스프링 보안과 떨어진 순수 로직이라 단위 테스트로 표 전체를 확인한다.
 * 경로에 렌탈 ID가 없는 범위(그 시점·자기 부스·{checkpointId})는 서버가 토큰으로 다시 확인한다.
 */
public class AccessPolicy {

    static final String RENTAL_ID = "rentalId";

    private final List<CompiledRule> rules;

    public AccessPolicy(List<AccessRule> rules) {
        PathPatternParser parser = new PathPatternParser();
        // 고정 경로(/rentals/ping)가 변수 경로(/rentals/{rentalId})보다 먼저 맞도록 구체적인 순서로 둔다
        this.rules = rules.stream()
                .map(rule -> new CompiledRule(rule, parser.parse(rule.path())))
                .sorted(Comparator.comparing(CompiledRule::pattern, PathPattern.SPECIFICITY_COMPARATOR))
                .toList();
    }

    /** 메서드·경로에 맞는 규칙과 경로 변수. 계약에 없는 operation이면 비어 있다. */
    public Optional<Match> match(String method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (CompiledRule compiled : rules) {
            if (!compiled.rule().method().equalsIgnoreCase(method)) {
                continue;
            }
            PathPattern.PathMatchInfo info = compiled.pattern().matchAndExtract(container);
            if (info != null) {
                return Optional.of(new Match(compiled.rule(), info.getUriVariables()));
            }
        }
        return Optional.empty();
    }

    /** principal이 null이면 토큰 없음. */
    public AccessDecision decide(String method, String path, TokenPrincipal principal) {
        return match(method, path)
                .map(m -> decide(m, principal))
                .orElse(AccessDecision.NOT_FOUND);
    }

    public AccessDecision decide(Match match, TokenPrincipal principal) {
        AccessRule rule = match.rule();
        if (rule.publicAccess()) {
            return AccessDecision.ALLOW;
        }
        if (principal == null) {
            return AccessDecision.UNAUTHENTICATED;
        }
        Optional<TokenRole> role = principal.role();
        if (role.isEmpty() || !rule.roles().contains(role.get())) {
            return AccessDecision.FORBIDDEN;
        }
        if (role.get() == TokenRole.CUSTOMER && !customerTokenKindAllowed(rule, principal)) {
            return AccessDecision.FORBIDDEN;
        }
        if (!rentalInScope(rule, principal, match.variables().get(RENTAL_ID))) {
            return AccessDecision.OUT_OF_SCOPE;
        }
        return AccessDecision.ALLOW;
    }

    /** 촬영 JWT는 captureToken인 경로만, 촬영 세트(scope CHECKPOINT)는 촬영 JWT만 받는다. */
    private static boolean customerTokenKindAllowed(AccessRule rule, TokenPrincipal principal) {
        boolean capture = principal.isCaptureToken();
        if (capture) {
            return rule.captureToken();
        }
        return rule.scope() != AccessScope.CHECKPOINT;
    }

    /** 경로에 {rentalId}가 있는 OWN_RENTAL만 gateway가 확인한다. ADMIN은 제한 없음. */
    private static boolean rentalInScope(AccessRule rule, TokenPrincipal principal, String rentalId) {
        if (rule.scope() != AccessScope.OWN_RENTAL || rentalId == null) {
            return true;
        }
        if (principal.role().filter(r -> r == TokenRole.CUSTOMER).isEmpty()) {
            return true;
        }
        if (principal.isCaptureToken()) {
            return rentalId.equals(principal.rentalId());
        }
        return principal.rentals().contains(rentalId);
    }

    public int size() {
        return rules.size();
    }

    public record Match(AccessRule rule, Map<String, String> variables) {
    }

    private record CompiledRule(AccessRule rule, PathPattern pattern) {
    }
}
