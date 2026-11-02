# 구현 계획: GW1 JWT 검증·역할×경로

**Branch**: `claude/project-thread-jodnl1` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)

## Summary

Spring Cloud Gateway 앞단에 Spring Security(reactive) 체인을 두고, auth-server JWKS로 JWT를 검증한 뒤 contracts `x-trace-access`에서 만든 `access-policy.yaml`로 역할·범위를 판단한다. 로그인·페어링 교환 라우트에는 Redis 고정 창 요청 제한 필터를 단다.

## Technical Context

- Java 21, Spring Boot 3.5.7, Spring Cloud Gateway 2025.0.3(WebFlux, `spring.cloud.gateway.server.webflux.routes`)
- Spring Security OAuth2 리소스 서버(reactive), `NimbusReactiveJwtDecoder` + `JwtValidators.createDefaultWithIssuer("trace")`
- 요청 제한: Redis(`spring-boot-starter-data-redis-reactive`), 키 `trace:gw:rl:<이름>:<IP>:<창 시작>`
- 테스트: WebTestClient + 테스트용 RSA 키·JWKS(reactor-netty 가짜 서버), JaCoCo 40% 이상
- 설정(환경 변수): `AUTH_SERVER_URL` 등 기존 5개, `REDIS_HOST·PORT·DATABASE·KEY_PREFIX`, `REDIS_PASSWORD`(trace-secrets), `TRACE_WEB_ORIGIN`(새로), `TRACE_JWT_ISSUER`(선택)

## Constitution Check

- 계약 우선: 역할·범위는 contracts에서 생성, 손으로 고치지 않음. 계약 변경 없음 ✓
- 비밀값: 코드·문서에 없음, 환경 변수 ✓
- trace 접두사: Redis 키 `trace:` ✓
- 테스트 이름은 수용 기준 문장 ✓

## 설계

| 구성 | 패키지 | 하는 일 |
| --- | --- | --- |
| `AccessPolicy`, `AccessRule`, `TokenPrincipal`, `AccessDecision` | `access.domain` | 경로 매칭(구체적인 경로 우선)과 역할·촬영 JWT·본인 렌탈 판단. 스프링 보안과 분리 |
| `AccessPolicyLoader` | `access.infra` | `access-policy.yaml` 읽기, 모르는 역할이면 시작 실패 |
| `SecurityConfig`, `PolicyAuthorizationManager`, `PublicPathBearerTokenConverter`, `AccessProblemHandlers` | `access.api` | 보안 체인, 공개 경로는 토큰을 읽지 않음, 401·403·404 Problem |
| `TraceHeadersGlobalFilter` | `access.api` | `X-User-Id·X-Role·X-Scope`를 지우고 토큰 값으로 다시 씀 |
| `IpRateLimitGatewayFilterFactory`, `RedisRateCounter` | `ratelimit` | IP당 분당 10회, 429 + Retry-After, Redis 장애 시 통과 |
| `scripts/gen_access_policy.py` | — | contracts → `access-policy.yaml`, `--check`로 어긋남 검사 |

### RequestRateLimiter 대신 작은 필터를 쓴 이유

기본 `RequestRateLimiter`는 429를 빈 본문으로 돌려주고 Redis 키 이름을 바꿀 수 없다. 계약은 429를 Problem(`RATE_LIMITED`) + `Retry-After`로 정했고, 인프라 규칙은 모든 키에 `trace:` 접두사다. 그래서 같은 자리(라우트 필터)에 60줄짜리 필터를 두었다.

### gateway가 막지 않는 범위

경로에 렌탈 ID가 없는 범위(`{checkpointId}`·`{setId}`·본문 checkpointId·부스)는 서버가 토큰으로 확인한다. 설계 규칙상 서버끼리 묻지 않으므로 gateway가 시점 → 렌탈을 알 방법이 없다. 서버 기능(CA1·DI1·RP1)의 명세에 이 확인을 넣는다.

## Project Structure

```
src/main/java/com/gakdo/trace/gateway/
  access/{domain,infra,api}/   common/ProblemWriter   ratelimit/
src/main/resources/access-policy.yaml   (생성 파일)
src/test/java/com/gakdo/trace/gateway/
  access/RoleRouteMatrix.java (rest-api-v0 표를 옮긴 기대값)  support/(TestTokens·StubBackend·InMemoryRateCounter)
scripts/gen_access_policy.py
```
