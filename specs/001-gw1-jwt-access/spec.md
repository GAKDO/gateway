# 기능 명세: GW1 JWT 검증·역할×경로

**Feature Branch**: `claude/project-thread-jodnl1` (Spec Kit 기본값은 `feature/001-gw1-jwt-access`, 이 세션의 지정 브랜치를 씀)

**Created**: 2026-11-02

**Status**: Converged

**Backlog**: GW1 (담당 A, 마감 11/5, 의존 AU1)

**Input**: "gateway가 모든 요청의 JWT를 auth-server JWKS로 검증하고, design/rest-api-v0.md의 역할 × 경로 표대로 막는다. 로그인 시도와 페어링 코드 입력에는 요청 제한을 건다(FR-AUTH-03). /api/v1/*/ping과 로그인·가입·검증 공개 페이지는 인증 없이 통과한다. 수용 기준: 역할 × 경로 표의 모든 칸이 테스트로 확인되고, 같은 IP에서 로그인을 연속 실패하면 제한된다. CORS는 web 주소만 허용한다."

## Clarifications

### Session 2026-11-02

민서가 "묻지 말고 진행, 정할 것만 모아서"를 요청해, 질문 대신 아래 기본값으로 정하고 진행했다. 바꾸려면 PR에서 알려 주면 된다.

- Q: 역할·범위 규칙의 원본은? → A: contracts develop의 OpenAPI operation별 `x-trace-access`(roles·scope·captureToken). gateway는 이 값을 스크립트로 `access-policy.yaml`에 옮겨 그대로 쓰고, 손으로 고치지 않는다. 테스트는 rest-api-v0.md 역할 × 경로 표를 따로 옮긴 기대값으로 두 원본이 같은지 확인한다.
- Q: 촬영 JWT와 로그인 CUSTOMER JWT는 어떻게 가르나? → A: 둘 다 `role=CUSTOMER`이고, 촬영 JWT만 `scope`(checkpointId) 클레임이 있다. `captureToken: false`인 경로는 촬영 JWT를 403으로 막는다. `scope: CHECKPOINT`이면서 CUSTOMER를 허용하는 경로(촬영 세트)는 촬영 JWT만 받는다.
- Q: "본인"·"그 시점"·"자기 부스" 범위는 어디서 막나? → A: 경로에 `{rentalId}`가 있으면 gateway가 `rentals` 클레임(촬영 JWT는 `rentalId` 클레임)과 비교해 403 `OUT_OF_SCOPE`를 낸다. 경로에 렌탈 ID가 없어 gateway가 알 수 없는 범위(`{checkpointId}`·`{setId}`·본문의 checkpointId·부스)는 서버가 토큰으로 다시 확인한다. 서버끼리 묻지 않는다는 설계 규칙 때문이다.
- Q: 요청 제한 기준은? → A: 계약의 429 설명대로 IP당 분당 10회. 로그인(`POST /auth/login`)과 페어링 코드 교환(`POST /auth/pairing-codes/exchange`)에만 건다. 실패만 세지 않고 시도를 센다(실패만 세려면 응답을 본 뒤 세야 해서 동시 요청에 뚫린다).
- Q: Redis가 죽으면? → A: 요청 제한만 꺼지고(통과) 경고 로그를 남긴다. 로그인 자체를 막지 않는다.
- Q: ID 전략 변경(11/2, 외부 ID는 NanoID 21자 PublicId, 헌법 1.1.0)에 gateway가 할 일은? → A: gateway는 경로 변수 모양을 검사하지 않는다(경로 템플릿으로만 맞춤). 형식 검사는 서버가 400으로 한다. `rentals`·`rentalId` 클레임과 경로 값을 문자열로 비교하므로 형식과 무관하다. 테스트의 ID 값은 NanoID 21자로 둔다.
- Q: web 주소는? → A: 환경 변수 `TRACE_WEB_ORIGIN`(쉼표로 여러 개). 도메인을 사면 gitops infra.env에 넣는다.

## 사용자 시나리오와 테스트 *(필수)*

### 사용자 이야기 1 - 역할에 맞는 요청만 서버에 닿는다 (우선순위: P1)

관리자·고객·촬영 중인 고객·부스·진단 에이전트·토큰 없는 방문자가 같은 gateway로 들어온다. gateway는 auth-server의 공개키로 JWT 서명·만료·발급자를 확인하고, 경로마다 허용된 역할만 서버로 보낸다. 서버는 `X-User-Id`·`X-Role`·`X-Scope` 헤더와 원래 `Authorization`을 받는다.

**이 우선순위인 이유**: FR-AUTH-03 필수. 모든 서버 기능(RE1·CA1·DI1·RP1)이 gateway 뒤에서 열린다.

**독립 테스트**: 테스트용 키로 만든 6종 토큰으로 계약의 모든 operation을 부르고, 역할 × 경로 표의 기대값과 비교한다.

**수용 시나리오**:

1. **Given** 토큰 없음, **When** 인증이 필요한 경로를 부르면, **Then** 401 `UNAUTHENTICATED` Problem Details.
2. **Given** 역할이 맞지 않는 토큰, **When** 경로를 부르면, **Then** 403 `FORBIDDEN`이고 서버에 닿지 않는다.
3. **Given** CUSTOMER 토큰의 `rentals`에 없는 렌탈, **When** `/rentals/{rentalId}`·`/reports/rentals/{rentalId}`를 부르면, **Then** 403 `OUT_OF_SCOPE`.
4. **Given** 서명이 틀리거나 만료됐거나 발급자가 `trace`가 아닌 토큰, **When** 보호된 경로를 부르면, **Then** 401.
5. **Given** 허용된 요청, **When** 서버에 전달되면, **Then** `X-User-Id`=sub, `X-Role`=role, `X-Scope`=scope가 붙고, 클라이언트가 보낸 같은 이름 헤더는 지워진다.
6. **Given** `/api/v1/*/ping`, 가입·인증·로그인·재설정·부스 토큰·페어링 교환·리프레시·검증 경로, **When** 토큰 없이(또는 깨진 토큰으로) 부르면, **Then** 서버에 그대로 전달된다.
7. **Given** `/.well-known/jwks.json`, **When** 밖에서 부르면, **Then** 전달하지 않는다(404). 공개키는 클러스터 안에서만 연다.

### 사용자 이야기 2 - 로그인과 페어링 코드 대입을 막는다 (우선순위: P1)

누군가 같은 IP에서 비밀번호나 6자리 페어링 코드를 계속 바꿔 넣는다. gateway는 IP당 분당 10회를 넘는 시도를 429로 돌려보낸다.

**독립 테스트**: 같은 IP로 로그인을 11번 보내면 11번째가 429 `RATE_LIMITED`이고 `Retry-After`가 있다. 다른 IP는 영향이 없다.

**수용 시나리오**:

1. **Given** 같은 IP, **When** 로그인을 1분 안에 연속 실패해 10회를 넘기면, **Then** 다음 요청은 429 `RATE_LIMITED`와 `Retry-After`.
2. **Given** 같은 조건, **When** 페어링 코드 교환을 10회 넘게 보내면, **Then** 429.
3. **Given** 다른 IP, **When** 같은 시간에 로그인하면, **Then** 제한되지 않는다.
4. **Given** Redis 장애, **When** 로그인하면, **Then** 제한 없이 통과하고 경고 로그를 남긴다.

### 사용자 이야기 3 - 브라우저는 web 주소에서만 부른다 (우선순위: P2)

web(8315)과 gateway(8314)는 출처가 달라 CORS가 필요하다. gateway는 `TRACE_WEB_ORIGIN`만 허용하고 리프레시 쿠키를 위해 자격 증명을 허용한다.

**수용 시나리오**:

1. **Given** web 출처의 사전 요청(OPTIONS), **When** 보내면, **Then** 200과 `Access-Control-Allow-Origin`=web 출처, `Access-Control-Allow-Credentials: true`.
2. **Given** 다른 출처, **When** 사전 요청을 보내면, **Then** 403이고 허용 헤더가 없다.

### Edge Cases

- 공개 경로에 만료 토큰을 실어 보내도(웹이 남은 토큰을 붙이는 경우) 401이 아니라 통과한다.
- 계약에 없는 경로·메서드는 404 `NOT_FOUND`(역할 판단 전에). 내부 경로가 우연히 열리지 않게 한다.
- `role` 클레임이 없거나 codes.yaml `auth.token_role`에 없는 값이면 403 `FORBIDDEN`.
- `/api/v1/rentals/{rentalId}`의 `{rentalId}` 자리에 `ping` 같은 고정 경로가 오면 고정 경로가 우선한다.
- 리프레시(`/auth/refresh`)는 쿠키 자격이라 gateway는 통과시키고 auth-server가 쿠키를 확인한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: gateway는 `Authorization: Bearer` JWT를 auth-server `/.well-known/jwks.json` 공개키(RS256, kid로 교체)로 검증하고 `iss=trace`, `exp`를 확인한다. (FR-AUTH-03)
- **FR-002**: 경로·메서드별 허용 역할·범위는 contracts OpenAPI `x-trace-access`에서 만든 `access-policy.yaml`을 따른다. `security: []`인 operation은 공개다.
- **FR-003**: 401은 `UNAUTHENTICATED`, 403은 `FORBIDDEN`·`OUT_OF_SCOPE`, 404는 `NOT_FOUND`, 429는 `RATE_LIMITED` 코드의 Problem Details(`application/problem+json`)로 답한다. (codes.yaml `error_code`)
- **FR-004**: 경로에 `{rentalId}`가 있고 범위가 `OWN_RENTAL`이면 CUSTOMER는 `rentals` 클레임, 촬영 JWT는 `rentalId` 클레임에 그 렌탈이 있어야 한다. ADMIN은 제한 없다.
- **FR-005**: 전달하는 요청에서 `X-User-Id`·`X-Role`·`X-Scope`를 지우고 토큰 값으로 다시 쓴다.
- **FR-006**: `POST /api/v1/auth/login`, `POST /api/v1/auth/pairing-codes/exchange`에 IP당 분당 10회 요청 제한(Redis, 키 `trace:gw:rl:<경로>:<IP>`). (FR-AUTH-03, mvp-design "gateway가 시도 횟수를 제한")
- **FR-007**: CORS는 `TRACE_WEB_ORIGIN`만 허용, 자격 증명 허용.
- **FR-008**: `/api/v1/gateway/ping`, `/actuator/health/**`는 인증 없이 연다. 기존 라우트·ping·Dockerfile을 유지한다.

### Key Entities

- **AccessRule**: method, 경로 템플릿, public 여부, roles, scope(NONE·OWN_RENTAL·CHECKPOINT·BOOTH), captureToken.
- **TokenPrincipal**: sub, role, scope, rentals, rentalId (JWT 클레임에서 읽음). 촬영 JWT = CUSTOMER + scope 있음.

## Success Criteria *(mandatory)*

- **SC-001**: 역할 × 경로 표 15행 × 6역할 = 90칸이 계약 operation 단위로 모두 테스트된다(operation 46개 × 6 주체).
- **SC-002**: 같은 IP에서 11번째 로그인이 429다.
- **SC-003**: `./mvnw verify` 통과, JaCoCo 줄 커버리지 40% 이상.
- **SC-004**: `access-policy.yaml`이 contracts develop과 다르면 생성 스크립트 `--check`가 실패한다.

## Assumptions

- AU1이 위 클레임(iss·sub·role·scope·rentals·rentalId·exp·jti)으로 RS256 JWT를 발급한다(contracts `bearerJwt` 설명).
- 서버(rental·capture·diagnostics·report)는 `{checkpointId}`·`{setId}`·본문 범위·부스 범위를 토큰으로 다시 확인한다.
- gateway 앞에 다른 프록시가 없다(NodePort 직접). 그래서 IP는 연결 주소를 쓰고 `X-Forwarded-For`를 믿지 않는다.
