# Agent OS 활성화 합의 리뷰 (RALPLAN Planner→Architect→Critic)

> 날짜: 2026-07-16
> 범위: Agent OS 활성화 전 변경 집합 — `docs/agent/` 운영 문서, `.claude/commands/os-*` 런북 9종, `.claude/settings.json` 훅 활성화, `.claudeignore`, `.mcp.json` un-ignore + 4서버 선언, `.github/workflows/claude_review.yml` + `.coderabbit.yaml` 이중 리뷰, `web/gateway`·`web/game` Sentry SDK 배선(`@sentry/nextjs`, env-DSN no-op 설계), `scripts/agent/` 가드 확장(.mcp.json 토큰 스캔), `.ai/` 상태층
> 계획 정본: `.omc/plans/2026-07-16-agent-os-activation-plan.md` (rev.3) · 스펙: `.omc/specs/deep-interview-opensamguk-ai-work-system.md` (ambiguity 19% PASSED)
> 리뷰 주체: Architect(독립 pass, Opus) + Critic(독립 pass, Opus) — 작성 레인과 분리된 별도 평가 레인
> Verdict: cleared

## 검토 이력

| 단계 | 판정 | 닫은 계약 |
|---|---|---|
| Planner rev.1 | — | 초안 (웨이브 순차) |
| Architect 리뷰 | SOUND-WITH-CHANGES (blocking 3 + advisory 7) | F1 `.mcp.json` git-ignored → un-ignore 스텝 없으면 W1 커밋이 no-op / F2 `check.py --strict`의 cross-agent critique가 리뷰 아티팩트 없이는 구조적 실패(부트스트랩 순환) / F3 플랫 0건 개명 게이트는 OMC 전역 스킬 참조를 오염 — 참조-인지 + 3버킷 분류로 교체 |
| Critic 리뷰 | APPROVED (fix-required 1 + improvement 3 + note 4) | #1 CodeRabbit fallback 기준을 AC-14·W1-4 스모크까지 일관 적용(부분 충족 금지) / #2 개명 게이트에 CLAUDE.md 출현 단위 판별 / #3 un-ignore 보상 가드레일(토큰 스캔) / #4 파이프라인 이음새 리스크 + W3-1 드라이런 |
| rev.3 병합 | **APPROVED** | 전 finding 계획 반영 완료, 사용자 전체 승인 (2026-07-16) |

## 영역별 판정 근거

- **`web/gateway` · `web/game` (Sentry SDK)**: 파리티 안전 — 프론트 전용 계측이며 데몬/ChangeRecorder/RNG/골든 경로 비접촉(Architect 확인). DSN은 env 주입, 미설정 시 `enabled:false`로 전체 no-op — 기존 동작 무변경이 기본값. 검증: 양 앱 `tsc --noEmit` 0 error + vitest green(gateway 3/3, game 148/148) + `next build` 양 앱 통과(라우트 테이블 정상 출력, DSN 미설정 no-op 경로).
- **`.github/workflows/claude_review.yml`**: 제네릭 영어 리뷰 = 러버스탬프 재생산(Architect T3) — 파리티-도메인 한국어 커스텀 프롬프트(RNG draw 순서·PhpRound half-away·로그 바이트 패리티·one-daemon-write·삽입 순서·위조 금지)가 AC-11 통과 조건으로 승격됨.
- **훅 활성화(`.claude/settings.json`)**: 전역/OMC 훅과 기능 중복 없음 실측(오케스트레이션 계층 vs 레포 가드), stdin JSON 실사격 5케이스 전부 기대 일치. `@`멘션 우회는 `.claudeignore`가 커버.
- **`.mcp.json` un-ignore**: 토큰 무포함 원칙 + `protect-sensitive-files.sh` §3 토큰 패턴 스캔(실사격 6케이스 통과)이 제거된 방어선을 보상.
- **골든/legacy/게임 로직**: 이 변경 집합은 `logic/`·`common/`·엔진 코드 비접촉 — 파리티 게이트 영향 없음.

## 잔여 조건 (cleared를 뒤집지 않는 후속)

- 원격 MCP 2종(atlassian·sentry)은 선언만 완료 — 인증 완료 판정은 실호출 1회 기준(`docs/agent/tool-capabilities.md`), 계정 준비 전까지 `채점대기`.
- CodeRabbit 설치·ANTHROPIC_API_KEY 시크릿은 인간 체크리스트 항목 — 미완이면 fallback 기준(Claude GHA 단독)으로 일괄 조정해 기록.
- prod 클라이언트 DSN 빌드 아그 배선은 `.ai/known-issues.md` 백로그.
