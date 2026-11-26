// 이 파일이 확인하는 것: 요구사항 FR-AUTH-03 "역할 × 경로 표의 모든 칸을 테스트로 확인한다".
// gateway를 실제로 띄우고, 모든 API 주소를 6가지 사용자(로그인 안 함·관리자·고객·촬영 JWT·부스·
// 진단 에이전트)로 하나씩 불러 표(RoleRouteMatrix)에 적힌 대로 통과·거절되는지 본다(약 440칸).

package com.gakdo.trace.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.gakdo.trace.gateway.access.RoleRouteMatrix.Case;
import com.gakdo.trace.gateway.access.RoleRouteMatrix.Expect;
import com.gakdo.trace.gateway.access.RoleRouteMatrix.Who;
import com.gakdo.trace.gateway.access.infra.AccessPolicyLoader;
import com.gakdo.trace.gateway.support.GatewayIntegrationTest;
import com.gakdo.trace.gateway.support.TestTokens;

/** FR-AUTH-03 수용 기준: 역할 × 경로 표의 모든 칸이 테스트로 확인된다. */
// GatewayIntegrationTest를 이어받아 gateway를 실제로 띄우고 가짜 서버를 뒤에 붙인 상태에서 시험한다
class RoleRouteMatrixIntegrationTest extends GatewayIntegrationTest {

    // 표의 모든 칸.
    static Stream<Case> cells() {
        // 표의 모든 칸(주소 × 사용자)을 하나씩 흘려보낸다(아래 반복 테스트의 입력)
        return RoleRouteMatrix.cases().stream();
    }

    /** 본인 칸은 남의 렌탈로도 한 번 더 부른다(경로에 렌탈 ID가 있을 때만 gateway가 막는다). */
    // "본인 렌탈만" 칸 중 주소에 렌탈 번호가 들어 있는 것(남의 렌탈로 한 번 더 시험할 대상).
    static Stream<Case> ownCellsWithRentalId() {
        // 표의 모든 칸 중에서
        return RoleRouteMatrix.cases().stream()
                // "본인만" 칸이면서 주소에 {rentalId}가 들어 있는 것만 고른다
                .filter(c -> c.expect() == Expect.OWN && c.op().template().contains("{rentalId}"));
    }

    // [상황] 표의 한 칸(주소 하나 × 사용자 하나)을 실제로 부른다. 렌탈 번호는 그 사람 본인 것.
    // [기대] 통과 칸은 뒤쪽 서버까지 전달(200), 401 칸은 UNAUTHENTICATED, 403 칸은 FORBIDDEN,
    //        404 칸은 NOT_FOUND로 답한다.
    //        ※ 응답 코드: 200 = 정상 처리됨, 401 = 로그인 필요(토큰이 없거나 만료·위조됨), 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일),
    //          404 = 찾을 수 없음(없는 주소나 데이터)
    // (도구) @ParameterizedTest + @MethodSource("cells") = cells()가 주는 칸마다 이 테스트를 한 번씩 반복 실행한다. name은 결과 화면에 칸 이름을 보여 준다
    @ParameterizedTest(name = "{0}")
    @MethodSource("cells")
    @DisplayName("역할 × 경로 표의 모든 칸이 테스트로 확인된다")
    void 역할_경로_표의_모든_칸(Case cell) {
        // 실행: 이 칸의 주소를 그 사용자 토큰으로, 본인 렌탈 번호를 넣어 부른다
        WebTestClient.ResponseSpec response = call(cell, TestTokens.OWN_RENTAL);
        // 확인: 표에 적힌 기대 결과에 따라 확인할 내용을 고른다
        switch (cell.expect()) {
            // (도구) expectStatus()/expectBody().jsonPath(...) = 받은 응답의 상태 코드와 JSON 칸 값을 확인한다
            // 통과 칸: 200이고 가짜 서버(stub)까지 전달됐는지 확인한다
            case OK, OWN -> response.expectStatus().isOk().expectBody().jsonPath("$.backend").isEqualTo("stub");
            // 401 칸: 로그인 필요(UNAUTHENTICATED)인지 확인한다
            case U -> response.expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
            // 403 칸: 권한 없음(FORBIDDEN)인지 확인한다
            case F -> response.expectStatus().isForbidden()
                    .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
            // 404 칸: 찾을 수 없음(NOT_FOUND)인지 확인한다
            case N -> response.expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
            // 표에 없는 기대 값이면 표가 잘못된 것이니 바로 실패시킨다
            default -> throw new IllegalStateException(cell.toString());
        }
    }

    // [상황] "본인 렌탈만" 칸을 이번에는 남의 렌탈 번호로 부른다.
    // [기대] 403 OUT_OF_SCOPE로 막는다(다른 고객의 렌탈·검사 결과를 볼 수 없음).
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    // "본인만" 칸마다 한 번씩 반복 실행한다
    @ParameterizedTest(name = "{0}")
    @MethodSource("ownCellsWithRentalId")
    @DisplayName("rentals 클레임에 없는 렌탈을 부르면 403 OUT_OF_SCOPE")
    void 남의_렌탈은_범위_밖(Case cell) {
        // 실행: 같은 칸을 남의 렌탈 번호로 부른다
        call(cell, TestTokens.OTHER_RENTAL)
                // 확인: 403(권한 없음)인지 확인한다
                .expectStatus().isForbidden()
                // 오류 코드가 "범위 밖"인지 확인한다
                .expectBody().jsonPath("$.code").isEqualTo("OUT_OF_SCOPE");
    }

    // [상황] 계약에서 만든 규칙표 파일의 주소 목록과, 손으로 옮긴 표의 주소 목록을 비교한다.
    // [기대] 둘이 정확히 같다. 계약에 주소가 늘었는데 표를 안 고치면 여기서 깨진다.
    @Test
    @DisplayName("표의 operation 목록이 contracts에서 만든 access-policy.yaml과 같다")
    void 표와_계약의_operation이_같다() {
        // 준비: 계약에서 만든 규칙표 파일(access-policy.yaml)을 읽어
        Set<String> fromPolicy = AccessPolicyLoader.load(new ClassPathResource("access-policy.yaml")).stream()
                // "방식 주소" 글자로 바꾸고
                .map(r -> r.method() + " " + r.path())
                // 중복 없는 묶음으로 모은다
                .collect(Collectors.toSet());
        // 손으로 옮긴 표의 주소 목록에서
        Set<String> fromTable = RoleRouteMatrix.OPERATIONS.stream()
                // /api/로 시작하는 것만 고르고
                .filter(op -> op.template().startsWith("/api/"))
                // "방식 주소" 글자로 바꾸고
                .map(op -> op.method() + " " + op.template())
                // 중복 없는 묶음으로 모은다
                .collect(Collectors.toSet());
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 확인: 두 목록이 정확히 같은지 확인한다(계약에 주소가 늘면 표도 고치게)
        assertThat(fromTable).isEqualTo(fromPolicy);
        // 칸 수가 주소 74개 × 사용자 6종인지 확인한다(빠진 칸이 없게)
        assertThat(RoleRouteMatrix.cases()).hasSize(74 * 6);
    }

    // 표의 한 칸을 실제 요청으로 바꿔 보낸다(사용자 종류에 맞는 테스트 토큰을 붙임).
    private WebTestClient.ResponseSpec call(Case cell, String rentalId) {
        // 주소 틀의 {rentalId} 같은 자리를 실제 값으로 채운다
        String path = RoleRouteMatrix.concrete(cell.op().template(), rentalId);
        // 칸에 적힌 방식(GET·POST 등)과 주소로 요청을 만든다
        WebTestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(cell.op().method())).uri(path);
        // 이 칸의 사용자 종류에 맞는 테스트 토큰을 고른다
        String token = token(cell.who());
        // 로그인한 사용자일 때만
        if (token != null) {
            // 토큰을 Authorization 헤더에 붙인다
            request.header("Authorization", "Bearer " + token);
        }
        // 요청을 실제로 보내고 응답 확인 객체를 돌려준다
        return request.exchange();
    }

    // 테스트 도구: 사용자 종류마다 맞는 테스트 토큰을 돌려준다
    private static String token(Who who) {
        // 사용자 종류에 따라 고른다
        return switch (who) {
            // 로그인 안 한 사용자는 토큰이 없다
            case PUBLIC -> null;
            // 관리자 토큰
            case ADMIN -> TestTokens.admin();
            // 고객 토큰
            case CUSTOMER -> TestTokens.customer();
            // 촬영 JWT
            case CAPTURE -> TestTokens.capture();
            // 부스 토큰
            case BOOTH -> TestTokens.booth();
            // 진단 에이전트 토큰
            case AGENT -> TestTokens.agent();
        };
    }
}
