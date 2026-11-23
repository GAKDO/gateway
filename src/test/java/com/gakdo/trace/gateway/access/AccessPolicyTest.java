// 이 파일이 확인하는 것: "누가 어떤 주소를 부를 수 있나" 규칙표(access-policy.yaml)를 판단하는 부분.
// TRACE 사용자 종류(역할): 관리자(ADMIN), 로그인 고객(CUSTOMER), 촬영 JWT(QR을 찍은 폰, 한 시점만),
// 부스(BOOTH, Jetson 촬영 부스), 진단 에이전트(AGENT, 노트북 진단 프로그램).
// 판단 결과: ALLOW 통과 / UNAUTHENTICATED 401 로그인 필요 / FORBIDDEN 403 권한 없음 /
// OUT_OF_SCOPE 403 남의 렌탈 / NOT_FOUND 404 계약에 없는 주소.
// 서버를 띄우지 않고 판단 함수만 바로 불러 본다.

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

    // 로그인 고객 토큰을 흉내 낸다. rentals = 이 고객에게 연결된 렌탈 번호들.
    private static TokenPrincipal customer(String... rentals) {
        return new TokenPrincipal("c", "CUSTOMER", null, Set.of(rentals), null);
    }

    // 촬영 JWT(QR을 찍은 폰)를 흉내 낸다. 시점 cp-1 하나와 렌탈 하나에만 쓸 수 있다.
    private static TokenPrincipal capture(String rentalId) {
        return new TokenPrincipal("c", "CUSTOMER", "cp-1", Set.of(), rentalId);
    }

    // [상황] 계약(contracts)에서 만든 규칙표 파일을 읽는다.
    // [기대] 주소 규칙 73개를 모두 읽는다. (테스트 이름의 56개는 처음 숫자이고, 계약이 늘며 73개가 됐다.)
    @Test
    void 계약의_api_operation_56개를_모두_읽는다() {
        assertThat(POLICY.size()).isEqualTo(73);
    }

    // [상황] "진단 다시 하기"(관리자가 고객에게 진단 에이전트를 다시 돌리게 하는 기능) 주소를 찾는다.
    // [기대] 관리자만 부를 수 있는 규칙으로 맞는다.
    @Test
    void 진단_다시_하기는_관리자만_연다() {
        assertThat(POLICY.match("POST", "/api/v1/diagnostics/checkpoints/" + R1 + "/redo")).get()
                .satisfies(m -> assertThat(m.rule().roles()).containsExactly(TokenRole.ADMIN));
    }

    // [상황] /rentals/ping 과 /rentals/{렌탈번호} 처럼 모양이 겹치는 주소를 부른다.
    // [기대] 글자가 고정된 ping 규칙이 먼저 맞아 공개로 열리고, 그 밖의 값은 렌탈 번호로 읽힌다.
    @Test
    void 고정_경로가_변수_경로보다_먼저_맞는다() {
        assertThat(POLICY.match("GET", "/api/v1/rentals/ping")).get()
                .extracting(m -> m.rule().publicAccess()).isEqualTo(true);
        assertThat(POLICY.match("GET", "/api/v1/rentals/" + R1)).get()
                .extracting(m -> m.variables().get("rentalId")).isEqualTo(R1);
    }

    // [상황] 렌탈 r-1을 가진 고객이 r-1과 남의 렌탈 r-2를 조회한다. 렌탈이 하나도 없는 고객도 r-2를 조회한다.
    // [기대] 자기 렌탈 r-1만 통과, 남의 렌탈은 403 OUT_OF_SCOPE.
    @Test
    void 로그인_고객은_rentals_클레임의_렌탈만_읽는다() {
        assertThat(POLICY.decide("GET", "/api/v1/rentals/" + R1, customer(R1))).isEqualTo(AccessDecision.ALLOW);
        assertThat(POLICY.decide("GET", "/api/v1/rentals/r-2", customer(R1))).isEqualTo(AccessDecision.OUT_OF_SCOPE);
        assertThat(POLICY.decide("GET", "/api/v1/rentals/r-2", customer())).isEqualTo(AccessDecision.OUT_OF_SCOPE);
    }

    // [상황] QR을 찍은 폰(촬영 JWT)이 촬영 진행률, 남의 렌탈 진행률, 리포트 전체를 부른다.
    // [기대] 자기 렌탈의 진행률만 볼 수 있다. 남의 렌탈은 OUT_OF_SCOPE, 리포트 전체는 403.
    @Test
    void 촬영_JWT는_그_렌탈의_진행률만_본다() {
        String stream = "/api/v1/reports/rentals/" + R1 + "/stream";
        assertThat(POLICY.decide("GET", stream, capture(R1))).isEqualTo(AccessDecision.ALLOW);
        assertThat(POLICY.decide("GET", "/api/v1/reports/rentals/r-2/stream", capture(R1)))
                .isEqualTo(AccessDecision.OUT_OF_SCOPE);
        assertThat(POLICY.decide("GET", "/api/v1/reports/rentals/" + R1, capture(R1)))
                .isEqualTo(AccessDecision.FORBIDDEN);
    }

    // [상황] 촬영 세트(사진 한 묶음)를 만드는 주소를 로그인 고객 토큰과 촬영 JWT로 각각 부른다.
    // [기대] 촬영은 QR로 받은 촬영 JWT로만 시작할 수 있고, 일반 로그인 토큰은 403.
    @Test
    void 촬영_세트는_로그인_고객_토큰으로_만들_수_없다() {
        assertThat(POLICY.decide("POST", "/api/v1/captures/sets", customer(R1))).isEqualTo(AccessDecision.FORBIDDEN);
        assertThat(POLICY.decide("POST", "/api/v1/captures/sets", capture(R1))).isEqualTo(AccessDecision.ALLOW);
    }

    // [상황] 사진 업로드 주소(사전 서명 URL)를 다시 받는 기능을 여러 사용자가 부른다.
    // [기대] 부스와 촬영 JWT만 통과. 고객·관리자는 403, 토큰 없으면 401, 계약에 없는 GET 방식은 404.
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

    // [상황] 관리자가 아무 렌탈이나 조회한다.
    // [기대] 관리자는 모든 렌탈을 볼 수 있다.
    @Test
    void ADMIN은_렌탈_범위_제한이_없다() {
        TokenPrincipal admin = new TokenPrincipal("a", "ADMIN", null, Set.of(), null);
        assertThat(POLICY.decide("GET", "/api/v1/rentals/any", admin)).isEqualTo(AccessDecision.ALLOW);
    }

    // [상황] 서명은 맞지만 역할(role) 값이 비어 있는 토큰으로 기기 목록을 부른다.
    // [기대] 역할이 없으니 403.
    @Test
    void 역할_클레임이_없으면_403() {
        TokenPrincipal noRole = new TokenPrincipal("x", null, null, null, null);
        assertThat(noRole.role()).isEmpty();
        assertThat(POLICY.decide("GET", "/api/v1/devices", noRole)).isEqualTo(AccessDecision.FORBIDDEN);
    }

    // [상황] 로그인 없이 기기 목록을 부르고, 계약에 없는 내부 주소(JWKS 공개키)를 부른다.
    // [기대] 기기 목록은 401(로그인 필요), 계약에 없는 주소는 404로 숨긴다.
    @Test
    void 토큰이_없으면_401_계약에_없으면_404() {
        assertThat(POLICY.decide("GET", "/api/v1/devices", null)).isEqualTo(AccessDecision.UNAUTHENTICATED);
        assertThat(POLICY.decide("GET", "/.well-known/jwks.json", null)).isEqualTo(AccessDecision.NOT_FOUND);
        assertThat(AccessDecision.NOT_FOUND.status()).isEqualTo(404);
    }

    // [상황] "공개도 아니고 허용 역할도 없는" 잘못된 규칙을 만들려 한다.
    // [기대] 만들 때 바로 오류를 낸다(실수로 아무도 못 쓰는 주소가 생기지 않게). 범위를 비우면 NONE이 된다.
    @Test
    void 공개가_아닌데_역할이_없는_규칙은_만들_수_없다() {
        Set<TokenRole> noRoles = Set.of();
        assertThatThrownBy(() -> new AccessRule("GET", "/x", false, noRoles, AccessScope.NONE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AccessRule("GET", "/x", false, Set.of(TokenRole.ADMIN), null, false).scope())
                .isEqualTo(AccessScope.NONE);
    }

    // [상황] 규칙표 파일에 없는 역할(ROOT)이 적혀 있거나, rules 칸이 아예 없다.
    // [기대] 서버가 켜질 때 오류로 멈춘다(오류 글에 ROOT가 보인다).
    @Test
    void 모르는_역할이_적힌_규칙_파일은_거부한다() {
        String yaml = "rules:\n- {method: GET, path: /x, roles: [ROOT], scope: NONE, captureToken: false}\n";
        ByteArrayResource unknownRole = new ByteArrayResource(yaml.getBytes());
        ByteArrayResource noRules = new ByteArrayResource("x: 1".getBytes());
        assertThatThrownBy(() -> AccessPolicyLoader.load(unknownRole))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROOT");
        assertThatThrownBy(() -> AccessPolicyLoader.load(noRules))
                .isInstanceOf(IllegalStateException.class);
    }

    // [상황] 파일 없이 "GET /a는 공개" 규칙 하나만 직접 만든다.
    // [기대] GET /a는 통과, 같은 주소의 POST는 계약에 없으니 404.
    @Test
    void 규칙_목록을_직접_만들어도_판단한다() {
        AccessPolicy policy = new AccessPolicy(List.of(AccessRule.publicRule("GET", "/a")));
        assertThat(policy.decide("GET", "/a", null)).isEqualTo(AccessDecision.ALLOW);
        assertThat(policy.decide("POST", "/a", null)).isEqualTo(AccessDecision.NOT_FOUND);
    }
}
