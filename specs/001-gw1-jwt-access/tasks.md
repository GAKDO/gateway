# Tasks: GW1 JWT 검증·역할×경로

## Phase 1: Setup

- [x] T001 pom에 oauth2-resource-server·data-redis-reactive 추가
- [x] T002 `scripts/gen_access_policy.py`로 contracts develop(de2ff0e)에서 `access-policy.yaml` 생성(규칙 45개)

## Phase 2: Foundational

- [x] T003 [P] `access.domain` 규칙·판단 로직 + `AccessPolicyTest`
- [x] T004 [P] `AccessPolicyLoader`(형식 오류면 시작 실패)
- [x] T005 `ProblemWriter`(contracts Problem + code)

## Phase 3: US1 역할에 맞는 요청만 서버에 닿는다 (P1)

- [x] T006 [US1] `SecurityConfig`: JWKS·iss 검증, 공개 경로는 토큰 무시, 인가 매니저
- [x] T007 [US1] 401·403·404 Problem 처리기
- [x] T008 [US1] `TraceHeadersGlobalFilter` 신원 헤더
- [x] T009 [US1] `RoleRouteMatrixIntegrationTest`: 46 operation × 6 주체 + 남의 렌탈 4건 + 표↔계약 operation 대조
- [x] T010 [US1] `TokenVerificationIntegrationTest`: 만료·다른 키·다른 발급자·깨진 토큰·모르는 역할·헤더 위조·404

## Phase 4: US2 로그인·페어링 대입 막기 (P1)

- [x] T011 [US2] `IpRateLimitGatewayFilterFactory` + `RedisRateCounter`, 라우트 `auth-login`·`auth-pairing-exchange`(order -1)
- [x] T012 [US2] 단위 테스트(IP 구분·Retry-After·XFF 무시·Redis 장애) + 통합 테스트(11번째 429)

## Phase 5: US3 CORS (P2)

- [x] T013 [US3] `TRACE_WEB_ORIGIN`만 허용, 자격 증명 허용 + `CorsIntegrationTest`

## Phase 6: Polish

- [x] T014 기존 `PingControllerTest`를 보안 제외 슬라이스로 유지
- [x] T015 `./mvnw verify` 통과(테스트 318개, 줄 커버리지 96%)
- [x] T016 README에 GW1 설정·규칙 재생성 방법
