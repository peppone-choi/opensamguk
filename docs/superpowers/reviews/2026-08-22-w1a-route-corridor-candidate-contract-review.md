# W1-A Route Corridor Candidate Contract Review

Scope: tools/map/
Verdict: cleared

Date: 2026-08-22

## Scope

- 기존 `han.json`의 무방향 연결 1,783개를 승인되지 않은 RouteNode-key 후보로 보존
- `external-places.json` 65행을 원본 그대로 격리한 PENDING 재고로 보존
- 제품 활성 시나리오 15개와 Han 지도 계약 리소스 31개를 분리
- W1-B/C의 mode, geometry, source claim, disposition, runtime activation은 비범위

## Independent findings

- BLOCKER: 0
- MAJOR: 0
- MINOR: 0

초기 리뷰의 두 MAJOR는 remediation 후 닫혔다.

1. Canonical corridor registry SHA를 코드 경계에 고정했다. coherent UUIDv4 회전,
   registry/candidate pair-key 교환, 원장 누락 CLI가 모두 fail-closed한다.
2. `validate_documents(documents, source, topology)`는 원본과 topology를 필수로 받는다.
   exact-key schema가 모든 문서·행·중첩 객체를 닫고, endpoint provenance,
   external raw 65행, scenario 31개 전체 record, provenance path/SHA/generator를 원본과 비교한다.

독립 변이 검사는 corridor approval/lifecycle/revision 별칭, 외부 type/location/lifecycle,
endpoint ID/fingerprint/disposition, scenario path/SHA/year/name/order, provenance 변조를 모두 거부했다.

## Verification evidence

- Python unit tests: 25/25
- 실제 generator `--check`: no drift
- Ruff: pass
- `py_compile`: pass
- basedpyright production files: 0 errors, 0 warnings, 0 notes
- Python no-excuse: 7 files, 0 violations
- pure LOC: builder 250, contract 239, semantic validator 203, source validator 165

Artifact SHA-256:

- contract: `fcc6a11031137ca97b73f17df464d517491a19853e7100ac8a472bcfecab6151`
- registry: `fd2e7a823245db1717d3751e3e5bdd813c3b649a4e0186669912ebcee4a1e8b3`
- corridor candidates: `6b086297b8113eb89ca1d4fedeb93a4500d4f748d733f95c71fef6d533b17e06`
- external candidates: `dd7359dee0060c941e563aaa3ed6ecf19086e700343518f940deae6d9bbd63fb`

## Tool-failure isolation

반복된 `fablize gate observed a tool failure`은 성공한 exit-0 read/test 명령에도 나타난
비특정 wrapper 경고였다. 실제 실패는 bare `ruff` PATH 부재(exit 127), no-excuse
검사기 탐색 무일치(exit 1), 존재하지 않는 리뷰 예시 경로 조회(exit 1)로 분리됐다.
`uvx ruff`, 설치된 Python checker의 정확한 경로, 본 리뷰 문서를 사용한 후 관련 게이트는
모두 통과했다. 제품 코드·데이터 검증 실패로 남은 항목은 없다.

## Residual boundaries

- 현재 1,783개는 승인 도로가 아니라 후보 topology다.
- 339개 후보가 W0의 무관 교체 endpoint에 닿으며, 그중 53개는 양끝이 교체됐다.
- 외부 65행의 `-9999..9999`는 승인 lifecycle이 아니라 review-required 원본 격리값이다.
- W1-B/C 완료 전 mode, geometry, source claim, 최종 edge count/hash, runtime 활성화를 주장하지 않는다.
