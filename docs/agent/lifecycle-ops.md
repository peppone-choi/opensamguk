# Lifecycle Runbook: Ops (배포·운영)

## Status

**ACTIVE** — `.github/workflows/deploy.yml`(main push 자동 배포), `scripts/deploy.sh`, `docker-compose.production.yml`, health check, 스모크(`tools/smoke.sh`)가 실동작. **Sentry는 프론트 2앱(`@sentry/nextjs`) + 백엔드 3앱(`sentry-spring-boot-starter-jakarta`, 에러 캡처 전용 — traces-sample-rate 0 고정)에 SDK 배선 + DSN 주입·적재 실증 완료**(org tekken-75, 서비스명 프로젝트 5개, 스모크 5/5 전송→회수 CONFIRMED, 2026-07-16 — ADR-LITE-008; prod 컨테이너 반영은 GCP VM `.env` 갱신 필요). Terraform/CloudWatch는 NOT_CONFIGURED — 백엔드 관측은 docker logs + prod DB + health 엔드포인트(+DSN 배선 후 Sentry).

## Read This When

배포 요청, prod 장애, 운영 설정 변경.

## ⚠️ 최우선 사실

**main push = 즉시 자동 배포(라이브 서버).** 따라서 main push 자체가 운영 행위이며 사람 승인 게이트 뒤에 있다.

## 자동 실행 금지 (사람 승인 필수)

- main push/merge(=프로덕션 배포), 수동 배포(`scripts/deploy.sh`)
- 운영 DB 변경(재시드, 파괴적 마이그레이션, 데이터 삭제 — 백업 선행)
- 운영 서비스 재시작(엔진 bounce는 메모리 상태 소유 — HARNESS §6)
- 비밀값 조회/출력, 리소스 생성·삭제
- `docker compose down -v`(볼륨 삭제 = DB 소실)

## Procedure — 배포

1. 사전: `tools/parity/gate.sh backend` green + 변경 앱 typecheck/build + critique `cleared`.
2. **사람 승인** 획득(명시적 go).
3. main push → `deploy.yml` 자동: 이미지 빌드→GHCR→GCP VM의 `gcp-prod` self-hosted runner→공유 스택 동기화→health. (수동 호환 경로: `scripts/deploy.sh 34.158.223.96 peppone_choi`.)
4. **nginx는 항상 최후** 재시작(정적 upstream — stale-DNS 502 예방, OPS LESSON A).
5. 검증(아래) 통과까지 "배포 완료" 선언 금지.

## Verification — 배포 후

- `/health`·`/actuator/health` green (필요조건일 뿐).
- **`world_state.current_year/current_month` 실전진 확인**(OPS LESSON B — 턴 동결 감지). 단, 어드민-생성-서버 체제에서 **빈 월드는 정상**: `world_state=0`·서버 목록 미렌더가 의도된 불변식(`WORKING_SYSTEM.md` §Production policy).
- 핵심 라우트(nginx 경유) 200 + 로그인 경로.
- 스모크: 로컬 재현 필요 시 `./tools/smoke.sh`.

## 장애 대응 (표준 5단계)

1. **감지·영향**: 증상을 데이터로 — 라우트/컨테이너/로그(`docker logs`), **프론트·백엔드 에러는 Sentry 대시보드/MCP 조회**(DSN 배선 후), 영향 범위.
2. **근본 원인**: `failure-cases.md`·HARNESS §6 두 ops lesson 먼저 대조(stale-DNS 502 → nginx 재시작 순서, 턴 동결 → `commandBlockMs`/Redis). 패턴 매칭이 안 맞으면 `systematic-debugging`으로 수렴 — **상태 변경 명령 전에 증거가 그 조치를 지지하는지 확인**.
3. **안전한 수정 제안** → 사람 검토.
4. **조치·검증**: 수정 → 배포 게이트 재통과 → 위 검증. Sentry 수집 대상(프론트·백엔드) 장애면 Sentry에서 해당 이슈 resolved + 재발 0 확인.
5. **회고**: 근본수정 + 재발 방지를 `failure-cases.md`/HARNESS/LEDGER에 기록(예: OPS LESSON A/B가 이 형식의 선례). 사용자 영향이 있었으면 아래 Post-mortem 템플릿으로.

## Post-mortem 템플릿 (사용자 영향 장애)

```md
# Post-mortem: <한 줄 증상> (YYYY-MM-DD)
- 영향: <기간 · 영향 라우트/기능 · 사용자 체감>
- 타임라인: 감지(어떻게) → 원인 확정 → 수정 배포 → 정상 확인 (각 시각)
- 근본 원인: <코드/설정/절차 — 증거 인용 (로그·Sentry 이슈 링크·커밋)>
- 재발 방지: <코드 수정 / 게이트 추가 / failure-cases.md 항목> (각각 완료 여부)
- 잘못 짚었던 가설: <있다면 — 다음 대응 시간 단축용>
```

## Rollback

전용 스크립트 없음(NOT_CONFIGURED). 실질 경로: 이전 커밋으로 main 되돌리기(승인 필수) → 자동 배포, 또는 GHCR 이전 태그로 compose 재기동. nginx-last 순서는 롤백에도 동일 적용.

## Tools / Commands

`gh run list/view`(Actions 상태), `docker compose -f docker-compose.production.yml ...`(GCP VM), `scripts/deploy.sh`, prod DB 쿼리(승인 하 read).

## Completion Criteria

health green + 턴 전진(또는 의도된 빈 월드) + 라우트 정상 + 회고 기록.

## State Files to Update

`.ai/current-state.md`, 장애 시 `failure-cases.md`·`.ai/known-issues.md`.

## Handoff Requirements

배포 커밋 SHA, 검증 관측 결과, 미해결 잔흔을 handoff에 기재.
