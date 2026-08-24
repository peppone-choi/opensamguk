# 오픈삼국 문서

> 마지막 검토: 2026-08-20  
> 적용 기준: 현재 저장소 코드, ADR-LITE-041/042, GitHub 이슈. Jira 실시간 상태는 권한 문제로 `UNKNOWN`.

이 디렉터리는 독자별 진입점입니다. 구현 증거와 과거 작업 기록을 한데 읽지 않아도 되도록 사용자, 관리자,
개발자, 기획 문서를 분리합니다.

## 독자별 시작점

| 독자 | 먼저 읽을 문서 | 다음 문서 |
|---|---|---|
| 플레이어 | [사용자 매뉴얼](./user/README.md) | [첫 시작](./user/getting-started.md), [게임플레이](./user/gameplay-guide.md), [기능 안내](./user/features.md) |
| 관리자·운영자 | [관리자 매뉴얼](./admin/README.md) | [서버 생명주기](./admin/server-lifecycle.md), [회원·게임 관리](./admin/member-and-game-management.md), [운영·복구](./admin/operations-and-recovery.md) |
| 개발자 | [기여 가이드](./CONTRIBUTING.md) | [아키텍처](../README.md#아키텍처), [검증](./CONTRIBUTING.md#최소-검증) |
| 게임 기획자 | [기획 문서 지도](./design/README.md) | [현재 로드맵](./design/roadmap.md), [v1/v2 경계](./design/v1-v2-boundary.md) |

## 문서 등급

| 등급 | 의미 | 대표 경로 |
|---|---|---|
| 제품 정본 | 승인된 제품 규칙과 경계 | `docs/superpowers/specs/`, `docs/design/` |
| 현재 매뉴얼 | 지금 사용 가능한 화면과 운영 절차 | `docs/user/`, `docs/admin/` |
| 실행 계획 | 아직 구현되지 않을 수 있는 작업 계약 | `docs/superpowers/plans/`, `docs/design/roadmap.md` |
| 증거·이력 | 특정 시점의 측정, 검토, 실패와 결정 | `docs/loops/`, `docs/superpowers/research/`, `docs/superpowers/reviews/` |
| 참고 원천 | 설계에 참고하되 현재 정답은 아닌 자료 | `legacy/`, 외부 Gitea 문서, `docs/wiki/` 로컬 코퍼스 |

`계획`을 `현재 기능`처럼 읽지 않습니다. 화면 route가 존재해도 live API·daemon 결과가 검증되지 않았다면
매뉴얼에서는 `제한`, `기획 중`, 또는 `UNKNOWN`으로 표시합니다.

## 이번 개편의 근거

- 외부 참고: devsam/core2026 `docs/` 34개, commit
  [`fafe4909`](https://gitea.hided.net/devsam/core2026/src/commit/fafe4909dcdaee82eb1457e7a6c4cf870595a6ca/docs)
- 적용/기각 내역: [core2026 문서 감사](./reference/core2026-docs-audit-2026-08-20.md)
- 문서 작성 규칙: [문서 기여 가이드](./CONTRIBUTING.md)

## 현재 동작 계약

- [계정 닉네임 표시 계약](./reference/account-nickname-identity.md): 변경 직후 세션 반영, 게시판의 현재값·역사
  폴백, 게임 랭킹의 장수명 경계를 정의합니다.

## 유지보수 원칙

1. 현재 동작, 승인된 방향, 제안을 명시적으로 구분합니다.
2. 비밀값·토큰·개인정보·실제 `.env` 내용은 문서에 쓰지 않습니다.
3. 사용자 문서는 내부 클래스명보다 화면과 결과를 먼저 설명합니다.
4. 관리자 문서는 파괴적 조작보다 승인·백업·복구 조건을 먼저 설명합니다.
5. 외부 티켓 상태는 확인 날짜와 함께 기록하고, 접근하지 못한 Jira 상태는 추측하지 않습니다.
