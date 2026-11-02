# 변경 기록

이 레포의 버전별 변경을 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다.
기능 PR은 `## [Unreleased]` 아래에 한 줄을 더하고, 릴리스 PR에서 `## [x.y.z] - 날짜`로 바꾼다(CONTRIBUTING.md 3번).
main에 버전이 적힌 CHANGELOG가 올라가면 release.yml이 태그와 GitHub Release를 만든다.

## [Unreleased]

### 추가
- 블로그(AIGJ-01-002-blog/devlog) CI/CD를 옮겨 초기 구성
- GW1 후속: contracts c0dfcba의 x-trace-access로 정책을 다시 만들어 `POST /api/v1/captures/sets/{setId}/uploads`(BOOTH·촬영 JWT)를 더함
