package com.gakdo.trace.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import com.gakdo.trace.gateway.access.domain.AccessDecision;
import com.gakdo.trace.gateway.access.domain.AccessPolicy;
import com.gakdo.trace.gateway.access.domain.AccessRule;
import com.gakdo.trace.gateway.access.domain.AccessScope;
import com.gakdo.trace.gateway.access.domain.TokenPrincipal;
import com.gakdo.trace.gateway.access.domain.TokenRole;
import com.gakdo.trace.gateway.access.infra.AccessPolicyLoader;

/** 스프링 없이 도는 역할×경로 판단 단위 테스트. */
class AccessPolicyTest {

    private static final String R1 = "r-1";
    private static final AccessPolicy POLICY = new AccessPolicy(
            AccessPolicyLoader.load(new ClassPathResource("access-policy.yaml")));

    private static TokenPrincipal customer(String... rentals) {
        return new TokenPrincipal("c", "CUSTOMER", null, Set.of(rentals), null);
    }

    private static TokenPrincipal capture(String rentalId) {
        return new TokenPrincipal("c", "CUSTOMER", "cp-1", Set.of(), rentalId);
    }

    @Test
    void 계약의_api_operation_56개를_모두_읽는다() {
        assertThat(POLICY.size()).isEqualTo(69);
    }

    @Test
    void 고정_경로가_변수_경로보다_먼저_맞는다() {
        assertThat(POLICY.match("GET", "/api/v1/rentals/ping")).get()
                .extracting(m -> m.rule().publicAccess()).isEqualTo(true);
        assertThat(POLICY.match("GET", "/api/v1/rentals/" + R1)).get()
                .extracting(m -> m.variables().get("rentalId")).isEqualTo(R1);
    }

    @Test
    void 로그인_고객은_rentals_클레임의_렌탈만_읽는다() {
        assertThat(POLICY.decide("GET", "/api/v1/rentals/" + R1, customer(R1))).isEqualTo(AccessDecision.ALLOW);
        assertThat(POLICY.decide("GET", "/api/v1/rentals/r-2", customer(R1))).isEqualTo(AccessDecision.OUT_OF_SCOPE);
        assertThat(POLICY.decide("GET", "/api/v1/rentals/r-2", customer())).isEqualTo(AccessDecision.OUT_OF_SCOPE);
    }

    @Test
    void 촬영_JWT는_그_렌탈의_진행률만_본다() {
        String stream = "/api/v1/reports/rentals/" + R1 + "/stream";
        assertThat(POLICY.decide("GET", stream, capture(R1))).isEqualTo(AccessDecision.ALLOW);
        assertThat(POLICY.decide("GET", "/api/v1/reports/rentals/r-2/stream", capture(R1)))
                .isEqualTo(AccessDecision.OUT_OF_SCOPE);
        assertThat(POLICY.decide("GET", "/api/v1/reports/rentals/" + R1, capture(R1)))
                .isEqualTo(AccessDecision.FORBIDDEN);
    }

    @Test
    void 촬영_세트는_로그인_고객_토큰으로_만들_수_없다() {
        assertThat(POLICY.decide("POST", "/api/v1/captures/sets", customer(R1))).isEqualTo(AccessDecision.FORBIDDEN);
        assertThat(POLICY.decide("POST", "/api/v1/captures/sets", capture(R1))).isEqualTo(AccessDecision.ALLOW);
    }

    @Test
    void 업로드_URL_다시_받기는_부스와_촬영_JWT만_부른다() {
        String uploads = "/api/v1/captures/sets/s-1/uploads";
        TokenPrincipal booth = new TokenPrincipal("b", "BOOTH", "booth-1", Set.of(), null);
        TokenPrincipal admin = new TokenPrincipal("a", "ADMIN", null, Set.of(), null);
        assertThat(POLICY.decide("POST", uploads, booth)).isEqualTo(AccessDecision.ALLOW);
        assertThat(POLICY.decide("POST", uploads, capture(R1))).isEqualTo(AccessDecision.ALLOW);
        assertThat(POLICY.decide("POST", uploads, customer(R1))).isEqualTo(AccessDecision.FORBIDDEN);
        assertThat(POLICY.decide("POST", uploads, admin)).isEqualTo(AccessDecision.FORBIDDEN);
        assertThat(POLICY.decide("POST", uploads, null)).isEqualTo(AccessDecision.UNAUTHENTICATED);
        assertThat(POLICY.decide("GET", uploads, booth)).isEqualTo(AccessDecision.NOT_FOUND);
    }

    @Test
    void ADMIN은_렌탈_범위_제한이_없다() {
        TokenPrincipal admin = new TokenPrincipal("a", "ADMIN", null, Set.of(), null);
        assertThat(POLICY.decide("GET", "/api/v1/rentals/any", admin)).isEqualTo(AccessDecision.ALLOW);
    }

    @Test
    void 역할_클레임이_없으면_403() {
        TokenPrincipal noRole = new TokenPrincipal("x", null, null, null, null);
        assertThat(noRole.role()).isEmpty();
        assertThat(POLICY.decide("GET", "/api/v1/devices", noRole)).isEqualTo(AccessDecision.FORBIDDEN);
    }

    @Test
    void 토큰이_없으면_401_계약에_없으면_404() {
        assertThat(POLICY.decide("GET", "/api/v1/devices", null)).isEqualTo(AccessDecision.UNAUTHENTICATED);
        assertThat(POLICY.decide("GET", "/.well-known/jwks.json", null)).isEqualTo(AccessDecision.NOT_FOUND);
        assertThat(AccessDecision.NOT_FOUND.status()).isEqualTo(404);
    }

    @Test
    void 공개가_아닌데_역할이_없는_규칙은_만들_수_없다() {
        assertThatThrownBy(() -> new AccessRule("GET", "/x", false, Set.of(), AccessScope.NONE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AccessRule("GET", "/x", false, Set.of(TokenRole.ADMIN), null, false).scope())
                .isEqualTo(AccessScope.NONE);
    }

    @Test
    void 모르는_역할이_적힌_규칙_파일은_거부한다() {
        String yaml = "rules:\n- {method: GET, path: /x, roles: [ROOT], scope: NONE, captureToken: false}\n";
        assertThatThrownBy(() -> AccessPolicyLoader.load(new ByteArrayResource(yaml.getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROOT");
        assertThatThrownBy(() -> AccessPolicyLoader.load(new ByteArrayResource("x: 1".getBytes())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 규칙_목록을_직접_만들어도_판단한다() {
        AccessPolicy policy = new AccessPolicy(List.of(AccessRule.publicRule("GET", "/a")));
        assertThat(policy.decide("GET", "/a", null)).isEqualTo(AccessDecision.ALLOW);
        assertThat(policy.decide("POST", "/a", null)).isEqualTo(AccessDecision.NOT_FOUND);
    }
}
