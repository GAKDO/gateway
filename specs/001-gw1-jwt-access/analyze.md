# 분석·수렴 결과 (speckit-analyze / converge)

| 확인 | 결과 |
| --- | --- |
| FR-001~008 ↔ 작업 | 모두 대응(T003~T013) |
| SC-001 역할 × 경로 전 칸 | 46 operation × 6 = 276칸 + 본인 범위 밖 4건, 통과 |
| SC-002 11번째 로그인 429 | 통과 |
| SC-003 verify·커버리지 | 통과, 줄 96% |
| SC-004 계약 어긋남 | `gen_access_policy.py --check` 일치(규칙 45개), 테스트가 표↔정책 operation 집합 대조 |
| 헌법 | 계약 변경 없음, 비밀값 없음, Redis 키 trace: 접두사 |

남은 위험

- AU1이 실제로 낼 클레임 이름(`role`·`scope`·`rentals`·`rentalId`)이 contracts 설명과 다르면 gateway가 모두 403을 낸다. AU1 구현 때 이 테스트의 `TestTokens`와 맞춰 본다.
- `TRACE_WEB_ORIGIN`이 gitops infra.env에 아직 없다(도메인 구매 뒤).

Status: **Converged**
