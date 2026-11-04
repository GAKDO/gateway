package com.gakdo.trace.gateway.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GAKDO/docs design/rest-api-v0.md "역할 × 경로 표"를 operation 단위 기대값으로 옮긴 것.
 * access-policy.yaml(contracts x-trace-access)과 따로 손으로 옮겨, 두 원본이 어긋나면 테스트가 깨진다.
 *
 * <p>열 순서: PUBLIC, ADMIN, CUSTOMER, 촬영, BOOTH, AGENT.
 * OK 허용, U 401, F 403 FORBIDDEN, N 404, OWN 본인 렌탈이면 허용·남의 렌탈이면 403 OUT_OF_SCOPE.
 * "그 시점만"·"자기 부스"·경로에 렌탈 ID가 없는 "본인"은 gateway에서 OK이고 서버가 토큰으로 다시 확인한다.
 */
final class RoleRouteMatrix {

    enum Who { PUBLIC, ADMIN, CUSTOMER, CAPTURE, BOOTH, AGENT }

    enum Expect { OK, U, F, N, OWN }

    record Operation(String group, String method, String template) {
    }

    record Case(Operation op, Who who, Expect expect) {
        @Override
        public String toString() {
            return op.group() + " | " + op.method() + " " + op.template() + " | " + who + " → " + expect;
        }
    }

    private static final Expect OK = Expect.OK;
    private static final Expect U = Expect.U;
    private static final Expect F = Expect.F;
    private static final Expect N = Expect.N;
    private static final Expect OWN = Expect.OWN;

    /** 표의 행 묶음 → 6열 기대값. */
    static final Map<String, Expect[]> GROUPS = Map.ofEntries(
            Map.entry("가입·인증·로그인·재설정·부스 토큰·페어링 교환·ping·검증", row(OK, OK, OK, OK, OK, OK)),
            Map.entry("내 정보·로그아웃", row(U, OK, OK, F, F, F)),
            Map.entry("탈퇴", row(U, F, OK, F, F, F)),
            Map.entry("QR 링크·페어링 코드 발급", row(U, OK, F, F, F, F)),
            Map.entry("QR 교환", row(U, F, OK, F, F, F)),
            Map.entry("기기·기종 목록, 렌탈·기기·기종 쓰기, 파일럿", row(U, OK, F, F, F, F)),
            // 부스 태블릿 기기 목록(contracts #22): 부스는 목록만, 진행 중 거르기는 rental-server가 한다
            Map.entry("렌탈 목록", row(U, OK, OWN, F, OK, F)),
            Map.entry("렌탈 읽기, 기종 치수 읽기", row(U, OK, OWN, F, F, F)),
            // 부스 코드로 렌탈 고르기(contracts booth-codes/redeem): 부스 토큰만
            Map.entry("부스 코드 교환", row(U, F, F, F, OK, F)),
            Map.entry("촬영 세트 생성·완료", row(U, F, F, OK, OK, F)),
            Map.entry("촬영 세트 상태", row(U, OK, F, OK, F, F)),
            Map.entry("탐지 결과 올리기", row(U, F, F, F, OK, F)),
            Map.entry("검사 결과·사진 URL 읽기", row(U, OK, OWN, F, F, F)),
            Map.entry("진단 올리기", row(U, F, F, F, F, OK)),
            Map.entry("진단 결과 읽기", row(U, OK, OWN, F, F, F)),
            Map.entry("리포트", row(U, OK, OWN, F, F, F)),
            Map.entry("진행률", row(U, OK, OWN, OWN, F, F)),
            Map.entry("체인 전체·검색", row(U, OK, F, F, F, F)),
            Map.entry("수동 봉인·사용 기록", row(U, OK, F, F, F, F)),
            Map.entry("내부(밖으로 열지 않음)", row(N, N, N, N, N, N)));

    private static final String PUB = "가입·인증·로그인·재설정·부스 토큰·페어링 교환·ping·검증";
    private static final String ADMIN_WRITE = "기기·기종 목록, 렌탈·기기·기종 쓰기, 파일럿";
    private static final String RENTAL_READ = "렌탈 읽기, 기종 치수 읽기";

    /** contracts의 operation 51개(ping 5개, JWKS 포함). */
    static final List<Operation> OPERATIONS = List.of(
            // auth-server
            op(PUB, "GET", "/api/v1/auth/ping"),
            op(PUB, "POST", "/api/v1/auth/signup"),
            op(PUB, "POST", "/api/v1/auth/email/verify"),
            op(PUB, "POST", "/api/v1/auth/email/resend"),
            op(PUB, "POST", "/api/v1/auth/login"),
            op(PUB, "POST", "/api/v1/auth/refresh"),
            op(PUB, "POST", "/api/v1/auth/password/reset-request"),
            op(PUB, "POST", "/api/v1/auth/password/reset"),
            op(PUB, "POST", "/api/v1/auth/booth/token"),
            op(PUB, "POST", "/api/v1/auth/pairing-codes/exchange"),
            op("내 정보·로그아웃", "GET", "/api/v1/auth/me"),
            op("내 정보·로그아웃", "POST", "/api/v1/auth/logout"),
            op("탈퇴", "DELETE", "/api/v1/auth/me"),
            op("QR 링크·페어링 코드 발급", "POST", "/api/v1/auth/capture-links"),
            op("QR 링크·페어링 코드 발급", "POST", "/api/v1/auth/pairing-codes"),
            op("QR 교환", "POST", "/api/v1/auth/capture-links/exchange"),
            op("내부(밖으로 열지 않음)", "GET", "/.well-known/jwks.json"),
            // rental-server
            op(PUB, "GET", "/api/v1/rentals/ping"),
            op(ADMIN_WRITE, "POST", "/api/v1/rentals"),
            op("렌탈 목록", "GET", "/api/v1/rentals"),
            op(RENTAL_READ, "GET", "/api/v1/rentals/{rentalId}"),
            // 시험 렌탈 지우기(contracts #25): 관리자만, TEST- 시리얼 확인은 rental-server가 한다
            op(ADMIN_WRITE, "DELETE", "/api/v1/rentals/{rentalId}"),
            op(ADMIN_WRITE, "POST", "/api/v1/rentals/{rentalId}/checkpoints"),
            op(ADMIN_WRITE, "POST", "/api/v1/rentals/{rentalId}/booth-code"),
            op("부스 코드 교환", "POST", "/api/v1/rentals/booth-codes/redeem"),
            op(ADMIN_WRITE, "POST", "/api/v1/rentals/{rentalId}/close"),
            op(ADMIN_WRITE, "POST", "/api/v1/devices"),
            op(ADMIN_WRITE, "GET", "/api/v1/devices"),
            op(ADMIN_WRITE, "PATCH", "/api/v1/devices/{deviceId}"),
            op(ADMIN_WRITE, "GET", "/api/v1/device-models"),
            op(RENTAL_READ, "GET", "/api/v1/device-models/{modelCode}"),
            op(ADMIN_WRITE, "PUT", "/api/v1/device-models/{modelCode}"),
            op(ADMIN_WRITE, "POST", "/api/v1/pilot/participants"),
            // capture-server
            op(PUB, "GET", "/api/v1/captures/ping"),
            op("촬영 세트 생성·완료", "POST", "/api/v1/captures/sets"),
            op("촬영 세트 생성·완료", "POST", "/api/v1/captures/sets/{setId}/uploads"),
            op("촬영 세트 생성·완료", "POST", "/api/v1/captures/sets/{setId}/complete"),
            op("탐지 결과 올리기", "POST", "/api/v1/captures/sets/{setId}/detections"),
            op("촬영 세트 상태", "GET", "/api/v1/captures/sets/{setId}"),
            op("검사 결과·사진 URL 읽기", "GET", "/api/v1/captures/checkpoints/{checkpointId}/inspection"),
            op("검사 결과·사진 URL 읽기", "GET", "/api/v1/captures/checkpoints/{checkpointId}/media"),
            // diagnostics-server
            op(PUB, "GET", "/api/v1/diagnostics/ping"),
            op("진단 올리기", "POST", "/api/v1/diagnostics/runs"),
            op("진단 결과 읽기", "GET", "/api/v1/diagnostics/checkpoints/{checkpointId}"),
            // report-server
            op(PUB, "GET", "/api/v1/reports/ping"),
            op("리포트", "GET", "/api/v1/reports/rentals/{rentalId}"),
            op("진행률", "GET", "/api/v1/reports/rentals/{rentalId}/stream"),
            op("체인 전체·검색", "GET", "/api/v1/reports/rentals/{rentalId}/chain"),
            op("수동 봉인·사용 기록", "POST", "/api/v1/reports/rentals/{rentalId}/checkpoints/{checkpointId}/seal"),
            op("수동 봉인·사용 기록", "GET", "/api/v1/reports/manual-seals"),
            op(PUB, "GET", "/api/v1/verify/{verifyToken}"),
            op("체인 전체·검색", "GET", "/api/v1/reports/search"));

    private RoleRouteMatrix() {
    }

    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        for (Operation op : OPERATIONS) {
            Expect[] row = GROUPS.get(op.group());
            if (row == null) {
                throw new IllegalStateException("표에 없는 묶음: " + op.group());
            }
            for (Who who : Who.values()) {
                cases.add(new Case(op, who, row[who.ordinal()]));
            }
        }
        return cases;
    }

    /** 경로 변수 채우기. rentalId는 본인 렌탈 또는 남의 렌탈. */
    static String concrete(String template, String rentalId) {
        return template
                .replace("{rentalId}", rentalId)
                .replace("{checkpointId}", com.gakdo.trace.gateway.support.TestTokens.CHECKPOINT)
                .replace("{setId}", "sEt0000000000000000aa")
                .replace("{deviceId}", "dEv000000000000000501")
                .replace("{modelCode}", "MBA13-M2")
                .replace("{verifyToken}", "v-token");
    }

    private static Expect[] row(Expect... values) {
        if (values.length != Who.values().length) {
            throw new IllegalArgumentException("열은 6개");
        }
        return values;
    }

    private static Operation op(String group, String method, String template) {
        return new Operation(group, method, template);
    }
}
