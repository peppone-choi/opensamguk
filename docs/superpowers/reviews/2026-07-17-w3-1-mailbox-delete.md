# W3-1 메일함 서신 삭제 배선 — 교차 비평 (작성 레인 / 리뷰 레인 분리)

> 날짜: 2026-07-17
> 범위: 커밋 `efea53d2` — `web/game/lib/api.ts`(deleteMessage 래퍼), `web/game/lib/mailbox.ts`(isMessageDeletable), `web/game/app/game/mailbox/page.tsx`(handleDelete + 삭제 버튼), 테스트 3파일(+14 tests). 백엔드 무변경(기존 라이브 경로 소비만).
> 티켓: OPENSAM-3(스토리) / OPENSAM-4(FE 배선) — Jira `OPENSAM`, 에픽 OPENSAM-1
> 리뷰 주체: 작성 = fe-submit-wirer(독립 서브에이전트) / 비평 = code-reviewer(별도 독립 레인, 레거시·백엔드 소스 직접 대조) — 저작·평가 레인 분리
> Verdict: cleared (fix-required 1건 해소 1왕복 후)

## 검토 이력

| 단계 | 판정 | 내용 |
|---|---|---|
| 1차 비평 | **fix-required 1** + improvement 3 + note 6 | 아래 fix-required 참조. 레거시 게이팅 미러(`testDeletable` 5조건, `src` 시멘틱, 시계 원천, ISO 파싱), API 계약(URL/body/매퍼 파싱), confirm byte-parity는 1차에서 이미 일치 판정 |
| fix 적용 | — | result 채널 폴링 + 주석 정정 + 테스트 재구성 (fe-submit-wirer) |
| 재심 | **cleared** | fix-required·improvement 3건 전부 실질 반영 확인 (tsc 0 + 대상 vitest 29/29 실행 검증) |

## fix-required (해소됨) — 인테이크 계약 오서술 + 엔진 deny 성공 위장

1차 구현은 "엔진 deny가 200 BLOCKED reason으로 표면화된다"고 주석·분기·테스트에 서술했으나 실제 계약과 다르다: `deleteMessage`는 인테이크 명령이라 `CommandController`(game-api web/)가 precheck Blocked/Unknown이어도 `isForecastReservable`로 **무조건 202 reserveAccepted 재라우팅**하고, 엔진 `MessageHandler.handleDelete`의 deny(본인 아님/5분 초과 등 PHP byte-parity 문자열)는 `GET /api/command/result/{requestId}`(RESOLVED + 톱레벨 ok/reason) 채널로만 전달된다. 그 결과 deny 분기는 도달 불가였고 엔진 거부가 "삭제했습니다" 성공 토스트로 위장됐다.

**해소**: 202 수신 후 `api.commandResult` 폴링(기존 select-pool 관례 20×300ms) — RESOLVED ok→확정+재조회 / RESOLVED !ok→엔진 reason 노출+목록 유지 / 미해결→접수 시멘틱 토스트. 주석은 실제 계약로 재서술, 도달 불가 분기는 방어 분기로 정직 주석 후 존치. 테스트는 4케이스(취소 무요청/확정/deny/타임아웃) + result 채널 계약 검증으로 재구성.

**파생 티켓**: 동일 패턴(202 후 result 미폴링 → 엔진 deny 조용한 no-op)이 기존 인테이크 배선 페이지 전반에 존재 — **OPENSAM-13**으로 전수 점검 등재. W3-1 handleDelete가 해소 패턴의 기준 구현.

## 잔여 note (비차단)

- `pendingId`가 폴링 완료(최대 ~6s)까지 유지 → 그동안 전 메시지 액션 비활성(이중 제출 방지 의도, select-pool 흐름과 일관).
- 테스트의 setTimeout 스텁이 폴링 간격 300ms 리터럴과 커플링 — 상수 변경 시 테스트 실대기(명시적 실패 아님).
- FE 5분 창은 클라이언트 시계(레거시도 동일 — 패러티 보존), 엔진 게이트는 `lastTurnTime` 기준으로 원천이 달라 경계 어긋남 가능(엔진이 authoritative, 기왕 설계).

## 검증 증거

- tsc `--noEmit` 0 error(fix 후 재확인) · `next build` 통과(fix 전 커밋 기준; fix는 페이지 로직/테스트만 변경)
- fix 전 풀 스위트 38 files/160 tests green. fix 후 대상 3파일 `mailbox.test.ts` 12(경계 2 포함) · `api-intake.test.ts` 13 · `MailboxPage.delete.test.tsx` 4 = **29/29 green, 독립 3회 재현**(작성자·비평자·오케스트레이터).
- fix 후 풀 스위트 병렬 실행은 호스트 부하(외부 프로세스 폭주, load avg 800+)로 광범위 타임아웃 플레이크 — 실패 파일(`admin1-route`, `GeneralBasicCard` 등 이번 변경 비접촉 파일) 단독 재실행 전부 green으로 부하 플레이크 판정(known-issues의 Testcontainers flake와 동일 분별 절차). 직렬(`--fileParallelism=false`) 풀 스위트 재검증 결과는 PR에 코멘트로 첨부.
- E2E(OPENSAM-5): 로컬 스택 기동 후 별도 판정 — 미완 시 채점대기로 기록. Docker Desktop이 인-컨테이너 gradle 빌드 중 크래시(VM 리소스 한계)해 스택 전략을 네이티브 백엔드 + Docker 인프라(postgres/redis)로 전환.
