# gateway

TRACE(노트북 렌탈 상태 기록 서비스)의 **gateway**: JWT 검증·경로 라우팅·요청 제한 (Spring Cloud Gateway)

- 담당: A
- 설계: [GAKDO/docs](https://github.com/GAKDO/docs) — 요구사항·아키텍처·ERD·REST API v0
- 기술: Java 21, Spring Boot 3.5.7, Spring Cloud Gateway (2025.0.3), Maven

## 실행

```bash
./mvnw spring-boot:run
```

```bash
curl http://localhost:8080/api/v1/gateway/ping
```

## 인증·인가 (GW1)

- 모든 요청의 `Authorization: Bearer` JWT를 auth-server `/.well-known/jwks.json`으로 검증합니다(RS256, `iss=trace`).
- 경로별 역할·범위는 [`access-policy.yaml`](src/main/resources/access-policy.yaml)을 따릅니다. 이 파일은 contracts OpenAPI의 `x-trace-access`에서 만든 **생성 파일**이라 손으로 고치지 않습니다.

```bash
python3 scripts/gen_access_policy.py ../contracts          # contracts develop을 받아 다시 만들기
python3 scripts/gen_access_policy.py ../contracts --check  # 어긋났는지 검사
```

- 거부는 Problem Details: 401 `UNAUTHENTICATED`, 403 `FORBIDDEN`·`OUT_OF_SCOPE`(경로의 `{rentalId}`가 토큰 `rentals`에 없음), 404 `NOT_FOUND`(계약에 없는 경로), 429 `RATE_LIMITED`.
- 서버에는 `X-User-Id`·`X-Role`·`X-Scope`를 토큰 값으로만 넘깁니다(클라이언트가 보낸 값은 지움). `{checkpointId}`·`{setId}`·부스 범위는 서버가 토큰으로 다시 확인합니다.
- 로그인·페어링 코드 교환은 IP당 분당 10회(Redis 키 `trace:gw:rl:*`). Redis가 죽으면 제한만 꺼집니다.

| 환경 변수 | 뜻 | 기본값(로컬) |
|---|---|---|
| `AUTH_SERVER_URL` 외 서버 4개 | 라우트 대상, JWKS 주소 | `http://<서버>-server:8080` |
| `REDIS_HOST`·`REDIS_PORT`·`REDIS_DATABASE`·`REDIS_KEY_PREFIX` | 요청 제한 카운터 | `localhost`·`6379`·`0`·`trace:` |
| `REDIS_PASSWORD` | trace-secrets | 없음 |
| `TRACE_WEB_ORIGIN` | CORS 허용 web 출처(쉼표로 여러 개) | `http://localhost:5173` |
| `TRACE_JWT_ISSUER` | JWT iss | `trace` |

## 테스트

```bash
./mvnw verify
```

줄 커버리지 40% 미만이면 실패합니다(블로그와 같은 기준).

## CI/CD

| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `backend-ci` | PR, develop push | 테스트 + JaCoCo 40% + (켜져 있으면) 학교 SonarQube |
| `배포` | PR(빌드만), develop·main push | 이미지 → GHCR(`ghcr.io/<조직>/gateway`). main이면 gitops 레포 이미지 태그 갱신 → Argo CD 배포 |
| `비밀값 검사` | PR, main·develop push | 학교 공용 비밀번호 패턴 + gitleaks |
| `release` | main에 CHANGELOG 버전 | 태그와 GitHub Release |
| `PR 알림`·`CI 결과 알림` | PR·CI 끝 | Discord·텔레그램 (변수로 켬) |

브랜치 규칙과 릴리스 절차는 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다.
