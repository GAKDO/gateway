# 변경 기록

이 레포의 버전별 변경을 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다.
기능 PR은 `## [Unreleased]` 아래에 한 줄을 더하고, 릴리스 PR에서 `## [x.y.z] - 날짜`로 바꾼다(CONTRIBUTING.md 3번).
main에 버전이 적힌 CHANGELOG가 올라가면 release.yml이 태그와 GitHub Release를 만든다.

## [Unreleased]

### 변경

- CI: SonarQube 분석 뒤 품질 게이트 판정까지 기다려 실행 요약·경고·알림에 통과/실패를 남김(이미지 빌드·배포는 막지 않음)
- 무중단 교체 준비: 뒤쪽 서버 이름(IP)을 5초만 기억하고 여러 IP를 번갈아 쓰며, 연결하지 못했을 때만 다른 IP로 다시 보냄(서버가 낸 5xx·시간 초과는 다시 보내지 않음). 이미지에 `site.gakdo.trace.upstream-rolling` 표시를 붙임

### 추가
- report-server 관리자 수동 봉인 `POST /api/v1/reports/rentals/{rentalId}/checkpoints/{checkpointId}/seal`·사용 기록 `GET /api/v1/reports/manual-seals` 접근 규칙(ADMIN만, contracts #11)

## [0.1.0] - 2026-11-02

**목표: 첫 클러스터 배포와 https://gakdo.site 공개**
gateway가 JWT를 검증하고 역할별로 경로를 막으며, Cloudflare Tunnel 뒤에서도 사용자별로 요청을 제한한다.

### 추가
- 블로그(AIGJ-01-002-blog/devlog) CI/CD를 옮겨 초기 구성
- JWT 검증·역할×경로 인가·요청 제한·CORS (GW1) (#1)
- Cloudflare 뒤에서 `CF-Connecting-IP`로 사용자 IP를 세는 요청 제한 (#2)
- 업로드 URL 다시 받기 경로 정책 (GW1 후속) (#3)

### 고침
- 사전 요청이 아닌 `OPTIONS`가 인가를 건너뛰던 문제, 계약에 없는 작업에 깨진 토큰이 붙으면 404 대신 401이 나던 문제 (#4)
- GW1 후속: contracts c0dfcba의 x-trace-access로 정책을 다시 만들어 `POST /api/v1/captures/sets/{setId}/uploads`(BOOTH·촬영 JWT)를 더함
