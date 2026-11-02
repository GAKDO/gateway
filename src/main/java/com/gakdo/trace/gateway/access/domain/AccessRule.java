package com.gakdo.trace.gateway.access.domain;

import java.util.Objects;
import java.util.Set;

/**
 * operation 하나의 접근 규칙. access-policy.yaml(contracts x-trace-access에서 생성) 한 항목과 같다.
 *
 * @param method       HTTP 메서드(대문자)
 * @param path         경로 템플릿(예: /api/v1/rentals/{rentalId})
 * @param publicAccess 토큰 없이 통과하는지
 * @param roles        허용 역할(공개면 비어 있음)
 * @param scope        범위
 * @param captureToken 촬영 JWT(CUSTOMER + scope)를 받는지
 */
public record AccessRule(String method, String path, boolean publicAccess, Set<TokenRole> roles, AccessScope scope,
                         boolean captureToken) {

    public AccessRule {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        roles = Set.copyOf(roles);
        scope = scope == null ? AccessScope.NONE : scope;
        if (!publicAccess && roles.isEmpty()) {
            throw new IllegalArgumentException(method + " " + path + ": 공개가 아닌데 허용 역할이 없습니다");
        }
    }

    public static AccessRule publicRule(String method, String path) {
        return new AccessRule(method, path, true, Set.of(), AccessScope.NONE, false);
    }
}
