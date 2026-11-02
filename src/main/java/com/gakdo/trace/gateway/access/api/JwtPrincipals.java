package com.gakdo.trace.gateway.access.api;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.gakdo.trace.gateway.access.domain.TokenPrincipal;

/** 검증된 JWT의 클레임을 TokenPrincipal로 바꾼다. 클레임 이름은 contracts bearerJwt 설명을 따른다. */
public final class JwtPrincipals {

    private JwtPrincipals() {
    }

    /** JWT 인증이 아니면 null(토큰 없음으로 본다). */
    public static TokenPrincipal from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            return null;
        }
        return from(token.getToken());
    }

    public static TokenPrincipal from(Jwt jwt) {
        return new TokenPrincipal(
                jwt.getSubject(),
                jwt.getClaimAsString("role"),
                jwt.getClaimAsString("scope"),
                rentals(jwt.getClaim("rentals")),
                jwt.getClaimAsString("rentalId"));
    }

    private static Set<String> rentals(Object claim) {
        Set<String> result = new LinkedHashSet<>();
        if (claim instanceof Collection<?> values) {
            values.forEach(v -> result.add(String.valueOf(v)));
        } else if (claim instanceof String value && !value.isBlank()) {
            result.add(value);
        }
        return result;
    }
}
