# Cross-agent critique — web/game pnpm lockfile overrides 재생성 (PR #151)

- 날짜: 2026-07-11
- 대상: `fix/web-game-lockfile-overrides` (커밋 `e60d789`), 변경 파일 `web/game/pnpm-lock.yaml` 단독
- 리뷰어: 독립 서브에이전트(cavecrew-reviewer, diff 전수 검사)

## 배경

`web/game/package.json`의 `pnpm.overrides`(postcss `^8.5.15`)가 lockfile에 `overrides:` 섹션으로
기록되지 않아 Docker 빌드(`docker/web-game.Dockerfile`)의 `pnpm install --frozen-lockfile`이
`ERR_PNPM_LOCKFILE_CONFIG_MISMATCH`로 실패, main 배포(run 29150844078, build-web game)가 중단됐다.
gateway 락파일에는 해당 섹션이 있어 game만 실패했고, 로컬은 기존 `node_modules`가 증상을 가렸다
(클린룸 3파일 복사 재현으로 확정).

## 수정

`pnpm install --lockfile-only --no-frozen-lockfile`로 lockfile 재생성 — `overrides: postcss: ^8.5.15`
섹션 기록 + postcss 잔존 해석 8.4.31 → 8.5.15 통일 (10 insertions, 17 deletions).

## 검증 증거

- 클린룸(3파일만 복사) frozen 설치: `Done in 5.9s using pnpm v10.33.0` — Docker 실패 지점 통과.
- `web/game` tsc: No errors found.
- `web/game` vitest: 143/143 pass.
- 독립 리뷰: diff lockfile-only 확인, package.json/의존성 드리프트 없음, postcss `^8.5.15`가
  모든 dependents 충족, 패키지 제거·integrity 이상 없음 — "No issues".

## 판정

Verdict: cleared

행동 변화 없는 빌드 인프라 픽스. 백엔드·RNG·로그 패러티 표면 비접촉. web/game 테스트 143/143 green.
