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

    static Stream<Case> cells() {
        return RoleRouteMatrix.cases().stream();
    }

    /** 본인 칸은 남의 렌탈로도 한 번 더 부른다(경로에 렌탈 ID가 있을 때만 gateway가 막는다). */
    static Stream<Case> ownCellsWithRentalId() {
        return RoleRouteMatrix.cases().stream()
                .filter(c -> c.expect() == Expect.OWN && c.op().template().contains("{rentalId}"));
    }

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownCellsWithRentalId")
    @DisplayName("rentals 클레임에 없는 렌탈을 부르면 403 OUT_OF_SCOPE")
    void 남의_렌탈은_범위_밖(Case cell) {
        call(cell, TestTokens.OTHER_RENTAL)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("OUT_OF_SCOPE");
    }

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
        assertThat(RoleRouteMatrix.cases()).hasSize(46 * 6);
    }

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
