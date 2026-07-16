# Current State

- Updated at: 2026-07-16 (Agent OS 활성화 세션 — W2-3 커밋/PR #154 + 백엔드 Sentry + Jira 연결)
- Active agent: Claude Code (합의 계획 실행 세션)
- Current branch: **main** (PR #154 **MERGED** 2026-07-16 14:31 UTC, 커밋 8건 — 사용자 머지 승인)
- Current phase: Wave 0 ✅ · Wave 1 저작 ✅ + 스모크(playwright ✅ · atlassian ✅ · Sentry 실증 ✅ · CodeRabbit 설치 ✅ / Claude GHA만 main 병합 대기) · Wave 2 W2-1/W2-2 ✅ + W2-3 커밋·PR ✅ · Wave 3 대기
- Completed (이번 세션 후반):
  - **W2-3**: 커밋 5건 + PR #154 오픈(push 사용자 승인) → 이후 백엔드 Sentry·Jira 배선 커밋 추가
  - **CodeRabbit 설치 확정** — `coderabbitai[bot]`이 PR #154에 코멘트. 단 57파일>50 제한으로 이 PR 리뷰는 스킵 → 실리뷰 판정은 W3-1 소형 PR
  - **Claude GHA**: PR 런 "success"는 의도적 스킵(신규 워크플로는 main 병합 후 실행 — 로그 실확인). `ANTHROPIC_API_KEY` 시크릿 등록 확인(2026-07-16 13:50 UTC, 이름 목록 검증)
  - **백엔드 Sentry 3앱 배선**(ADR-LITE-008, 사용자 승인 "지금 이 PR에 추가"): `sentry-spring-boot-starter-jakarta` 8.49.0, 에러 캡처 전용(traces-sample-rate 0 고정·send-default-pii false), DSN 빈 값 → no-op, compose 서비스별 DSN 매핑(`SENTRY_DSN_*`), `.env.example` 갱신
  - **Jira 연결 완료**: `/mcp` 재동의 → pepponechoi-jira(cloudId 300c260a-…) 인증 + W1-2 티켓 스모크 PASS(`SCRUM-5` 생성→완료 전환). 사용자 URL의 PEPPO-2는 Atlassian **Home** 프로젝트(Jira 아님)로 판명 → 사용자가 실운영 프로젝트 **`OPENSAM`**("오픈삼국", id 10001) 생성, MCP create 권한·이슈타입 실확인
  - **`.mcp.json` atlassian SSE→Streamable HTTP**(`/v1/mcp`) 마이그레이션 — Atlassian 지원 종료 공지(2026-06-30) 대응
- Verification run:
  - 3앱 gradle test XML: gateway-api 73/0/0 · game-api 394(1건 postgres 컨테이너 기동 flake → 단독 재실행 green) · game-engine 557/0/0(skip 1 = `LongSimReplayGateTest`, 기왕 P5 백로그)
  - compose 2종 `docker compose config` + `SENTRY_DSN` 렌더 확인
  - `check.py --strict --base origin/main` → **No findings**
- Verification run (Sentry, 2026-07-16 추가): org tekken-75 프로젝트 5개 서비스명 rename(플랫폼으로 DSN 매핑 확정) → DSN 로컬 `.env`+앱별 `.env.local` 주입(전부 git-ignored) → 스모크 이벤트 5/5 전송·API 회수 CONFIRMED. 가드 훅의 `.env.local` Write 차단 실사격 확인
- Verification NOT run: Sentry SDK 실행 경로(앱 기동→에러 적재 — 다음 로컬 스택 기동 시), Claude GHA 실리뷰(main 병합 대기), CodeRabbit 실리뷰(소형 PR 대기)
- Failed approaches: 1차 Atlassian OAuth가 구(suspended) 사이트에만 부여 → 신규 사이트 호출이 "isn't explicitly granted" 거부 → `/mcp` 재동의(사이트 선택)로 해결 (`tool-capabilities.md` 온보딩에 기록)
- Open questions (사람 결정 필요): ① Sentry 사용자 토큰 회전(채팅 노출 이력 — 회전 후 로컬 `.env`만 갱신) ② Wave 3 착수 시점(사용자 "요금 납부 후 정지 풀리면 더 하자" — 재개 신호 대기)
- **EC2 prod 요금 미납 정지**(2026-07-16): prod 작업 전부 보류(`known-issues.md` 운영 잔흔 절). 머지가 발화한 `deploy.yml` 런 + 6h queued 잔여 런 취소 처리. Claude GHA 워크플로는 main 반영 완료 — 다음 PR부터 실행(armed)
- Next action: 사용자 재개 신호 후 Wave 3 (W3-1 F4 소형 갭 풀 파이프라인 — Jira `OPENSAM` 티켓 + CodeRabbit·Claude GHA 실리뷰 판정 겸함, EC2 불필요 → W3-2 DB 인덱스). EC2 해제 후: prod 백로그(deploy 재발화, EC2 `.env` DSN, 빌드 아그)
- Must-read files for next action: `.omc/plans/2026-07-16-agent-os-activation-plan.md`, `.ai/decisions.md`(ADR-LITE-008), `.omc/artifacts/w1-tool-wiring-gate.md`

> 이 파일은 마지막 갱신 시점의 스냅샷이다. 오래됐으면 `git log --oneline -10`과 `docs/loops/*/LEDGER.md`로 교차 검증하라.
