package com.gakdo.trace.gateway.access.domain;

import java.util.Optional;
import java.util.Set;

/**
 * 검증된 JWT에서 인가에 쓰는 값만 뽑은 것.
 *
 * @param subject  sub (서버에 X-User-Id로 넘김)
 * @param rawRole  role 클레임 원문(서버에 X-Role로 넘김)
 * @param scope    scope 클레임(checkpointId 또는 boothId). 로그인 토큰에는 없다
 * @param rentals  rentals 클레임(로그인한 CUSTOMER가 볼 수 있는 렌탈 ID)
 * @param rentalId rentalId 클레임(촬영 JWT·AGENT JWT)
 */
public record TokenPrincipal(String subject, String rawRole, String scope, Set<String> rentals, String rentalId) {

    public TokenPrincipal {
        rentals = rentals == null ? Set.of() : Set.copyOf(rentals);
    }

    public Optional<TokenRole> role() {
        return TokenRole.parse(rawRole);
    }

    /** 촬영 JWT: role CUSTOMER이면서 시점(scope)이 묶인 토큰. 로그인 CUSTOMER 토큰에는 scope가 없다. */
    public boolean isCaptureToken() {
        return role().filter(r -> r == TokenRole.CUSTOMER).isPresent() && scope != null && !scope.isBlank();
    }
}
