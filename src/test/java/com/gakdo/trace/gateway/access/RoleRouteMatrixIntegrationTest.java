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
class RoleRouteMatrixIntegrationTest extends GatewayIntegrationTest {

    // 표의 모든 칸.
    static Stream<Case> cells() {
        return RoleRouteMatrix.cases().stream();
    }

    /** 본인 칸은 남의 렌탈로도 한 번 더 부른다(경로에 렌탈 ID가 있을 때만 gateway가 막는다). */
    // "본인 렌탈만" 칸 중 주소에 렌탈 번호가 들어 있는 것(남의 렌탈로 한 번 더 시험할 대상).
    static Stream<Case> ownCellsWithRentalId() {
        return RoleRouteMatrix.cases().stream()
                .filter(c -> c.expect() == Expect.OWN && c.op().template().contains("{rentalId}"));
    }

    // [상황] 표의 한 칸(주소 하나 × 사용자 하나)을 실제로 부른다. 렌탈 번호는 그 사람 본인 것.
    // [기대] 통과 칸은 뒤쪽 서버까지 전달(200), 401 칸은 UNAUTHENTICATED, 403 칸은 FORBIDDEN,
    //        404 칸은 NOT_FOUND로 답한다.
    //        ※ 응답 코드: 200 = 정상 처리됨, 401 = 로그인 필요(토큰이 없거나 만료·위조됨), 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일),
    //          404 = 찾을 수 없음(없는 주소나 데이터)
    @ParameterizedTest(name = "{0}")
    @MethodSource("cells")
    @DisplayName("역할 × 경로 표의 모든 칸이 테스트로 확인된다")
    void 역할_경로_표의_모든_칸(Case cell) {
        WebTestClient.ResponseSpec response = call(cell, TestTokens.OWN_RENTAL);
        switch (cell.expect()) {
            case OK, OWN -> response.expectStatus().isOk().expectBody().jsonPath("$.backend").isEqualTo("stub");
            case U -> response.expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
            case F -> response.expectStatus().isForbidden()
                    .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
            case N -> response.expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
            default -> throw new IllegalStateException(cell.toString());
        }
    }

    // [상황] "본인 렌탈만" 칸을 이번에는 남의 렌탈 번호로 부른다.
    // [기대] 403 OUT_OF_SCOPE로 막는다(다른 고객의 렌탈·검사 결과를 볼 수 없음).
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @ParameterizedTest(name = "{0}")
    @MethodSource("ownCellsWithRentalId")
    @DisplayName("rentals 클레임에 없는 렌탈을 부르면 403 OUT_OF_SCOPE")
    void 남의_렌탈은_범위_밖(Case cell) {
        call(cell, TestTokens.OTHER_RENTAL)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("OUT_OF_SCOPE");
    }

    // [상황] 계약에서 만든 규칙표 파일의 주소 목록과, 손으로 옮긴 표의 주소 목록을 비교한다.
    // [기대] 둘이 정확히 같다. 계약에 주소가 늘었는데 표를 안 고치면 여기서 깨진다.
    @Test
    @DisplayName("표의 operation 목록이 contracts에서 만든 access-policy.yaml과 같다")
    void 표와_계약의_operation이_같다() {
        Set<String> fromPolicy = AccessPolicyLoader.load(new ClassPathResource("access-policy.yaml")).stream()
                .map(r -> r.method() + " " + r.path())
                .collect(Collectors.toSet());
        Set<String> fromTable = RoleRouteMatrix.OPERATIONS.stream()
                .filter(op -> op.template().startsWith("/api/"))
                .map(op -> op.method() + " " + op.template())
                .collect(Collectors.toSet());
        assertThat(fromTable).isEqualTo(fromPolicy);
        assertThat(RoleRouteMatrix.cases()).hasSize(74 * 6);
    }

    // 표의 한 칸을 실제 요청으로 바꿔 보낸다(사용자 종류에 맞는 테스트 토큰을 붙임).
    private WebTestClient.ResponseSpec call(Case cell, String rentalId) {
        String path = RoleRouteMatrix.concrete(cell.op().template(), rentalId);
        WebTestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(cell.op().method())).uri(path);
        String token = token(cell.who());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.exchange();
    }

    private static String token(Who who) {
        return switch (who) {
            case PUBLIC -> null;
            case ADMIN -> TestTokens.admin();
            case CUSTOMER -> TestTokens.customer();
            case CAPTURE -> TestTokens.capture();
            case BOOTH -> TestTokens.booth();
            case AGENT -> TestTokens.agent();
        };
    }
}
