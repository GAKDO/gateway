// 이 파일이 확인하는 것: "누가 어떤 주소를 부를 수 있나" 규칙표(access-policy.yaml)를 판단하는 부분.
// TRACE 사용자 종류(역할): 관리자(ADMIN), 로그인 고객(CUSTOMER), 촬영 JWT(QR을 찍은 폰, 한 시점만),
// 부스(BOOTH, Jetson 촬영 부스), 진단 에이전트(AGENT, 노트북 진단 프로그램).
// 판단 결과: ALLOW 통과 / UNAUTHENTICATED 401 로그인 필요 / FORBIDDEN 403 권한 없음 /
// OUT_OF_SCOPE 403 남의 렌탈 / NOT_FOUND 404 계약에 없는 주소.
// 서버를 띄우지 않고 판단 함수만 바로 불러 본다.
// ※ 응답 코드: 401 = 로그인 필요(토큰이 없거나 만료·위조됨), 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일),
//   404 = 찾을 수 없음(없는 주소나 데이터)

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

    // 테스트에 쓰는 렌탈 번호
    private static final String R1 = "r-1";
    // 계약에서 만든 규칙표 파일을 읽어 판단기를 한 번 만들어 둔다(모든 테스트가 같이 씀)
    private static final AccessPolicy POLICY = new AccessPolicy(
            AccessPolicyLoader.load(new ClassPathResource("access-policy.yaml")));

    // 로그인 고객 토큰을 흉내 낸다. rentals = 이 고객에게 연결된 렌탈 번호들.
    private static TokenPrincipal customer(String... rentals) {
        // 사용자 c, 역할 CUSTOMER, 범위 없음, 받은 렌탈 번호들로 신원 정보를 만든다
        return new TokenPrincipal("c", "CUSTOMER", null, Set.of(rentals), null);
    }

    // 촬영 JWT(QR을 찍은 폰)를 흉내 낸다. 시점 cp-1 하나와 렌탈 하나에만 쓸 수 있다.
    private static TokenPrincipal capture(String rentalId) {
        // 사용자 c, 역할 CUSTOMER, 범위 cp-1, 렌탈 하나로 신원 정보를 만든다
        return new TokenPrincipal("c", "CUSTOMER", "cp-1", Set.of(), rentalId);
    }

    // [상황] 계약(contracts)에서 만든 규칙표 파일을 읽는다.
    // [기대] 주소 규칙 73개를 모두 읽는다. (테스트 이름의 56개는 처음 숫자이고, 계약이 늘며 73개가 됐다.)
    @Test
    void 계약의_api_operation_56개를_모두_읽는다() {
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 실행+확인: 읽은 규칙 수가 73개인지 확인한다
        assertThat(POLICY.size()).isEqualTo(73);
    }

    // [상황] "진단 다시 하기"(관리자가 고객에게 진단 에이전트를 다시 돌리게 하는 기능) 주소를 찾는다.
    // [기대] 관리자만 부를 수 있는 규칙으로 맞는다.
    @Test
    void 진단_다시_하기는_관리자만_연다() {
        // 실행: "진단 다시 하기" 주소에 맞는 규칙을 찾고, 찾았는지(get) 확인한다
        assertThat(POLICY.match("POST", "/api/v1/diagnostics/checkpoints/" + R1 + "/redo")).get()
                // 확인: 그 규칙의 허용 역할이 ADMIN 하나뿐인지 확인한다
                .satisfies(m -> assertThat(m.rule().roles()).containsExactly(TokenRole.ADMIN));
    }

    // [상황] /rentals/ping 과 /rentals/{렌탈번호} 처럼 모양이 겹치는 주소를 부른다.
    // [기대] 글자가 고정된 ping 규칙이 먼저 맞아 공개로 열리고, 그 밖의 값은 렌탈 번호로 읽힌다.
    @Test
    void 고정_경로가_변수_경로보다_먼저_맞는다() {
        // 실행+확인: /rentals/ping에 맞는 규칙을 찾는다
        assertThat(POLICY.match("GET", "/api/v1/rentals/ping")).get()
                // (도구) extracting(...) = 결과에서 한 값만 뽑아 그 값을 확인한다
                // 그 규칙이 공개(로그인 없이)인지 확인한다
                .extracting(m -> m.rule().publicAccess()).isEqualTo(true);
        // /rentals/r-1에 맞는 규칙을 찾는다
        assertThat(POLICY.match("GET", "/api/v1/rentals/" + R1)).get()
                // 주소의 r-1이 렌탈 번호(rentalId)로 읽혔는지 확인한다
                .extracting(m -> m.variables().get("rentalId")).isEqualTo(R1);
    }

    // [상황] 렌탈 r-1을 가진 고객이 r-1과 남의 렌탈 r-2를 조회한다. 렌탈이 하나도 없는 고객도 r-2를 조회한다.
    // [기대] 자기 렌탈 r-1만 통과, 남의 렌탈은 403 OUT_OF_SCOPE.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    void 로그인_고객은_rentals_클레임의_렌탈만_읽는다() {
        // 실행+확인: r-1을 가진 고객이 r-1을 조회하면 통과인지 확인한다
        assertThat(POLICY.decide("GET", "/api/v1/rentals/" + R1, customer(R1))).isEqualTo(AccessDecision.ALLOW);
        // 같은 고객이 남의 렌탈 r-2를 조회하면 "범위 밖"인지 확인한다
        assertThat(POLICY.decide("GET", "/api/v1/rentals/r-2", customer(R1))).isEqualTo(AccessDecision.OUT_OF_SCOPE);
        // 렌탈이 하나도 없는 고객도 "범위 밖"인지 확인한다
        assertThat(POLICY.decide("GET", "/api/v1/rentals/r-2", customer())).isEqualTo(AccessDecision.OUT_OF_SCOPE);
    }

    // [상황] QR을 찍은 폰(촬영 JWT)이 촬영 진행률, 남의 렌탈 진행률, 리포트 전체를 부른다.
    // [기대] 자기 렌탈의 진행률만 볼 수 있다. 남의 렌탈은 OUT_OF_SCOPE, 리포트 전체는 403.
    @Test
    void 촬영_JWT는_그_렌탈의_진행률만_본다() {
        // 준비: r-1의 촬영 진행률 주소를 만든다
        String stream = "/api/v1/reports/rentals/" + R1 + "/stream";
        // 실행+확인: r-1 촬영 JWT로 자기 진행률은 통과인지 확인한다
        assertThat(POLICY.decide("GET", stream, capture(R1))).isEqualTo(AccessDecision.ALLOW);
        // 남의 렌탈 진행률은
        assertThat(POLICY.decide("GET", "/api/v1/reports/rentals/r-2/stream", capture(R1)))
                // "범위 밖"인지 확인한다
                .isEqualTo(AccessDecision.OUT_OF_SCOPE);
        // 자기 렌탈이라도 리포트 전체는
        assertThat(POLICY.decide("GET", "/api/v1/reports/rentals/" + R1, capture(R1)))
                // "권한 없음"인지 확인한다
                .isEqualTo(AccessDecision.FORBIDDEN);
    }

    // [상황] 촬영 세트(사진 한 묶음)를 만드는 주소를 로그인 고객 토큰과 촬영 JWT로 각각 부른다.
    // [기대] 촬영은 QR로 받은 촬영 JWT로만 시작할 수 있고, 일반 로그인 토큰은 403.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    void 촬영_세트는_로그인_고객_토큰으로_만들_수_없다() {
        // 실행+확인: 로그인 고객 토큰으로 촬영 세트를 만들면 "권한 없음"인지 확인한다
        assertThat(POLICY.decide("POST", "/api/v1/captures/sets", customer(R1))).isEqualTo(AccessDecision.FORBIDDEN);
        // 촬영 JWT로는 통과인지 확인한다
        assertThat(POLICY.decide("POST", "/api/v1/captures/sets", capture(R1))).isEqualTo(AccessDecision.ALLOW);
    }

    // [상황] 사진 업로드 주소(사전 서명 URL)를 다시 받는 기능을 여러 사용자가 부른다.
    // [기대] 부스와 촬영 JWT만 통과. 고객·관리자는 403, 토큰 없으면 401, 계약에 없는 GET 방식은 404.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 401 = 로그인 필요(토큰이 없거나 만료·위조됨),
    //          404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    void 업로드_URL_다시_받기는_부스와_촬영_JWT만_부른다() {
        // 준비: 업로드 URL 다시 받기 주소를 만든다
        String uploads = "/api/v1/captures/sets/s-1/uploads";
        // 부스 신원(범위 booth-1)을 만든다
        TokenPrincipal booth = new TokenPrincipal("b", "BOOTH", "booth-1", Set.of(), null);
        // 관리자 신원을 만든다
        TokenPrincipal admin = new TokenPrincipal("a", "ADMIN", null, Set.of(), null);
        // 실행+확인: 부스는 통과인지 확인한다
        assertThat(POLICY.decide("POST", uploads, booth)).isEqualTo(AccessDecision.ALLOW);
        // 촬영 JWT도 통과인지 확인한다
        assertThat(POLICY.decide("POST", uploads, capture(R1))).isEqualTo(AccessDecision.ALLOW);
        // 로그인 고객은 "권한 없음"인지 확인한다
        assertThat(POLICY.decide("POST", uploads, customer(R1))).isEqualTo(AccessDecision.FORBIDDEN);
        // 관리자도 "권한 없음"인지 확인한다
        assertThat(POLICY.decide("POST", uploads, admin)).isEqualTo(AccessDecision.FORBIDDEN);
        // 토큰이 없으면(null) "로그인 필요"인지 확인한다
        assertThat(POLICY.decide("POST", uploads, null)).isEqualTo(AccessDecision.UNAUTHENTICATED);
        // 계약에 없는 GET 방식은 "없는 주소"인지 확인한다
        assertThat(POLICY.decide("GET", uploads, booth)).isEqualTo(AccessDecision.NOT_FOUND);
    }

    // [상황] 관리자가 아무 렌탈이나 조회한다.
    // [기대] 관리자는 모든 렌탈을 볼 수 있다.
    @Test
    void ADMIN은_렌탈_범위_제한이_없다() {
        // 준비: 렌탈 목록이 비어 있는 관리자 신원을 만든다
        TokenPrincipal admin = new TokenPrincipal("a", "ADMIN", null, Set.of(), null);
        // 실행+확인: 아무 렌탈이나 조회해도 통과인지 확인한다
        assertThat(POLICY.decide("GET", "/api/v1/rentals/any", admin)).isEqualTo(AccessDecision.ALLOW);
    }

    // [상황] 서명은 맞지만 역할(role) 값이 비어 있는 토큰으로 기기 목록을 부른다.
    // [기대] 역할이 없으니 403.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    void 역할_클레임이_없으면_403() {
        // 준비: 역할·범위·렌탈이 모두 비어 있는 신원을 만든다
        TokenPrincipal noRole = new TokenPrincipal("x", null, null, null, null);
        // 역할이 "없음"으로 읽히는지 먼저 확인한다
        assertThat(noRole.role()).isEmpty();
        // 실행+확인: 기기 목록을 부르면 "권한 없음"인지 확인한다
        assertThat(POLICY.decide("GET", "/api/v1/devices", noRole)).isEqualTo(AccessDecision.FORBIDDEN);
    }

    // [상황] 로그인 없이 기기 목록을 부르고, 계약에 없는 내부 주소(JWKS 공개키)를 부른다.
    // [기대] 기기 목록은 401(로그인 필요), 계약에 없는 주소는 404로 숨긴다.
    //        ※ 응답 코드: 401 = 로그인 필요(토큰이 없거나 만료·위조됨), 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    void 토큰이_없으면_401_계약에_없으면_404() {
        // 실행+확인: 토큰 없이 기기 목록을 부르면 "로그인 필요"인지 확인한다
        assertThat(POLICY.decide("GET", "/api/v1/devices", null)).isEqualTo(AccessDecision.UNAUTHENTICATED);
        // 계약에 없는 공개키 주소는 "없는 주소"인지 확인한다
        assertThat(POLICY.decide("GET", "/.well-known/jwks.json", null)).isEqualTo(AccessDecision.NOT_FOUND);
        // "없는 주소" 판단이 응답 코드 404로 바뀌는지 확인한다
        assertThat(AccessDecision.NOT_FOUND.status()).isEqualTo(404);
    }

    // [상황] "공개도 아니고 허용 역할도 없는" 잘못된 규칙을 만들려 한다.
    // [기대] 만들 때 바로 오류를 낸다(실수로 아무도 못 쓰는 주소가 생기지 않게). 범위를 비우면 NONE이 된다.
    @Test
    void 공개가_아닌데_역할이_없는_규칙은_만들_수_없다() {
        // 준비: 빈 역할 묶음을 만든다
        Set<TokenRole> noRoles = Set.of();
        // (도구) assertThatThrownBy(() -> ...) = 괄호 안 코드를 실행했을 때 오류가 나야 한다는 확인
        // 실행+확인: 공개도 아니고 역할도 없는 규칙을 만들면
        assertThatThrownBy(() -> new AccessRule("GET", "/x", false, noRoles, AccessScope.NONE, false))
                // "잘못된 값" 오류가 나는지 확인한다
                .isInstanceOf(IllegalArgumentException.class);
        // 범위를 비워(null) 만든 규칙은
        assertThat(new AccessRule("GET", "/x", false, Set.of(TokenRole.ADMIN), null, false).scope())
                // 범위가 NONE으로 채워지는지 확인한다
                .isEqualTo(AccessScope.NONE);
    }

    // [상황] 규칙표 파일에 없는 역할(ROOT)이 적혀 있거나, rules 칸이 아예 없다.
    // [기대] 서버가 켜질 때 오류로 멈춘다(오류 글에 ROOT가 보인다).
    @Test
    void 모르는_역할이_적힌_규칙_파일은_거부한다() {
        // 준비: 없는 역할(ROOT)이 적힌 규칙표 내용을 만든다
        String yaml = "rules:\n- {method: GET, path: /x, roles: [ROOT], scope: NONE, captureToken: false}\n";
        // 그 내용을 파일처럼 읽을 수 있게 감싼다
        ByteArrayResource unknownRole = new ByteArrayResource(yaml.getBytes());
        // rules 칸이 아예 없는 파일 내용도 만든다
        ByteArrayResource noRules = new ByteArrayResource("x: 1".getBytes());
        // 실행+확인: ROOT가 적힌 파일을 읽으면
        assertThatThrownBy(() -> AccessPolicyLoader.load(unknownRole))
                // 상태 오류가 나고
                .isInstanceOf(IllegalStateException.class)
                // 오류 글에 ROOT가 보이는지 확인한다(무엇이 틀렸는지 알 수 있게)
                .hasMessageContaining("ROOT");
        // rules 칸이 없는 파일을 읽으면
        assertThatThrownBy(() -> AccessPolicyLoader.load(noRules))
                // 역시 상태 오류가 나는지 확인한다
                .isInstanceOf(IllegalStateException.class);
    }

    // [상황] 파일 없이 "GET /a는 공개" 규칙 하나만 직접 만든다.
    // [기대] GET /a는 통과, 같은 주소의 POST는 계약에 없으니 404.
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    void 규칙_목록을_직접_만들어도_판단한다() {
        // 준비: "GET /a는 공개" 규칙 하나로 판단기를 만든다
        AccessPolicy policy = new AccessPolicy(List.of(AccessRule.publicRule("GET", "/a")));
        // 실행+확인: 토큰 없이 GET /a는 통과인지 확인한다
        assertThat(policy.decide("GET", "/a", null)).isEqualTo(AccessDecision.ALLOW);
        // 같은 주소의 POST는 "없는 주소"인지 확인한다
        assertThat(policy.decide("POST", "/a", null)).isEqualTo(AccessDecision.NOT_FOUND);
    }
}
