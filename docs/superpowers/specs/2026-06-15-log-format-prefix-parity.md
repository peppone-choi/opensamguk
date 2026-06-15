# 로그 formatText 접두 패러티 — spec (2026-06-15)

> 출처: bug-parity 루프 바퀴 16/17/18 파생 발견. 짝 LEDGER: `docs/loops/bug-parity-2026-06-15/LEDGER.md`
> (백로그 "로그 formatText 접두 갭"). 본 문서는 **계획만** — 구현은 승인 후 단계별 게이트 바퀴로.
> **§3 결정 1건은 패러티-계약 결정이라 유저 승인 대기**(루프 규칙: 규칙/계약 변경 = 승인 필수).

## 1. 문제 (2계층 divergence)

PHP `ActionLogger`는 push 시점에 `formatText(formatType)`로 **접두를 stored-text 에 굽는다**
(`ActionLogger.php:231-270`, push 메서드 `:119-215`). opensamguk 은 **2계층 모두 다르게** 구현돼 있다:

- **엔진(저장)**: `LogEntryDraft.text` 를 **raw(접두 없음)** 로 저장. `DatabaseHooks.toLogRow`
  (`DatabaseHooks.kt:480`)가 text verbatim + year/month 를 **별도 컬럼**으로 flush 시점에 stamp.
  `LogEntryDraft.format: Int?` 필드는 존재하나 **미소비**(`TurnWorldModel.kt:123`).
- **프론트(렌더)**: `web/game/.../world-log/page.tsx:95`는 `{year}년 {month}월`을 **별도 컬럼** +
  text 인라인(`dangerouslySetInnerHTML`)으로 렌더. `formatLog`(`lib/utilGame/formatLog.ts`)는
  색/태그 마크업→HTML 변환만 하고 **날짜 접두는 넣지 않는다**. (legacy `PageHistory.vue:41`는
  `v-html=formatLog(item)`로 접두-**포함** text 를 인라인 렌더 — 행별 날짜컬럼 없음.)

⇒ **stored log_entry.text 가 PHP 와 byte-不일치**(접두 누락). 장기-시뮬 Phase 4 "full-stored-bytes"
게이트의 선결 조건이며, 현재 라이브는 날짜를 컬럼으로 보여줘 **화면상 깨지진 않는다**(잠재 byte 갭).
**백엔드만 접두를 추가하면 FE 가 날짜를 이중 표시**(컬럼 + 인라인)하는 회귀가 난다.

## 2. formatType 인벤토리 (PHP 정본 — 위조 금지 오라클)

`ActionLogger.php:23-39`(const) + `:231-270`(formatText). 접두 문자열 byte-정확:

| const | int | 접두 템플릿 | 날짜 |
|---|---|---|---|
| RAWTEXT | 0 | `{text}` | 없음 |
| PLAIN | 1 | `<C>●</>{text}` | 없음 |
| YEAR_MONTH | 2 | `<C>●</>{year}년 {month}월:{text}` | 연·월 |
| YEAR | 3 | `<C>●</>{year}년:{text}` | 연 |
| MONTH | 4 | `<C>●</>{month}월:{text}` | 월 |
| EVENT_PLAIN | 5 | `<S>◆</>{text}` | 없음 |
| EVENT_YEAR_MONTH | 6 | `<S>◆</>{year}년 {month}월:{text}` | 연·월 |
| NOTICE | 7 | `<R>★</>{text}` | 없음 |
| NOTICE_YEAR_MONTH | 8 | `<R>★</>{year}년 {month}월:{text}` | 연·월 |

push-site → 기본 formatType → 저장 채널(`ActionLogger.php:119-215`):

| push 메서드 | 기본 formatType | 채널 |
|---|---|---|
| pushGeneralHistoryLog | YEAR_MONTH | general_record(history) |
| pushGeneralActionLog | MONTH | general_record(action) |
| pushGeneralBattleResultLog | RAWTEXT | general_record(battle_result) |
| pushGeneralBattleDetailLog | PLAIN | general_record(battle_detail) |
| pushNationalHistoryLog | YEAR_MONTH | nation_history |
| pushGlobalActionLog | MONTH | global_log(action) |
| pushGlobalHistoryLog | YEAR_MONTH | global_log(history) |

**중요:** battle(RAWTEXT/PLAIN)·EVENT/NOTICE 변종은 날짜 접두가 **없거나** bullet 기호가 다르다 —
일괄 YEAR_MONTH 적용은 오답. push-site별 formatType 를 **정확히** 매핑해야 한다.
또한 PHP 접두의 `{year}/{month}`는 logger **생성 시점**(push 시점) 날짜다 — 엔진은 flush 시점
world date(`toLogRow`의 year/month)로 stamp. 월틱-내 로그는 일치하나, 턴이 월경계를 넘는 mid-turn
로그(전투/행동)는 미세 불일치 가능 — 단계별로 검증 대상.

## 3. 결정 (DECISION REQUIRED — 유저 승인 대기)

opensamguk 은 log_entry 에 **year/month 별도 컬럼**을 둔 구조 divergence 를 이미 채택했다(질의/필터
유리). 패러티 계약을 둘 중 하나로 **확정**해야 한다:

- **A. PHP byte-match(접두를 text 에 굽기):** `toLogRow`가 `LogEntryDraft.format`로 formatText 적용 →
  stored text 가 PHP 와 byte-동일. **장기-시뮬 Phase 4 stored-bytes 게이트 직접 성립.** 대가: FE 전
  로그 surface 가 text **인라인** 렌더로 통일(날짜컬럼 제거, legacy 와 동일) — 안 그러면 이중날짜.
  year/month 컬럼은 질의용으로 유지(표시엔 미사용).
- **B. 구조 divergence 유지(접두 없이 컬럼):** stored text=raw, FE 가 컬럼으로 날짜 표시. 장기-시뮬
  게이트는 양측을 **정규화**(Kotlin: text+year+month 에 formatText 재구성 / 또는 PHP 접두 제거)해
  비교. 라이브 화면은 현행 유지. 대가: "Korean log byte-parity"(CLAUDE.md 규율 3)를 stored-bytes
  수준이 아닌 **재구성 동치** 수준으로 정의 — 규율 문구 해석 변경이라 승인 필수.

**추천: A** — 규율 3(로그 byte-parity)과 0.9.0 풀패러티 우선([[project_versioning_0_9_parity]])에 부합,
게이트가 단순(정규화 레이어 불요). 단 FE 4-surface 재작업 비용. **승인 전 구현 착수 금지.**

## 4. 단계 (각 단계 = 측정→1가설→fresh 재채점→채택/원복 1바퀴)

- **P0 (무Docker, 승인 불요):** `formatText` 를 common/엔진에 충실 포팅(9 formatType 템플릿 + int 상수)
  + 단위 테스트(PHP `:231-270` 대조, byte). 아직 호출처 없음 — 순수 추가.
- **P1 (무Docker):** 엔진 전 `LogEntryDraft(...)` 생성처에 push-site별 `format` 채움(인벤토리 §2 맵).
  **행동 변경 0**(format 아직 미소비) — 메타데이터 배선 + 테스트. blast radius=WorldActionContext +
  ReservedTurnHandler + MonthlyPostUpdateHook + intake/auction 등(Explore 인벤토리 그룹).
- **P2 (승인 후 — §3 결정 반영):**
  - A 선택 시: `toLogRow`가 format→formatText 적용 + 접두-없음 가정 **기존 백엔드 테스트 assertion
    정정**(완화 아님, PHP-정답으로 교정) + FE world-log/history/admin7/MyInfoLogPanel 인라인 렌더로
    통일(날짜컬럼 제거). surface별 1바퀴.
  - B 선택 시: read-layer/FE 가 표시 시 formatText 재구성 + 게이트 정규화 레이어.
- **P3 (Docker):** PHP stored-bytes 골든 캡처(general_record/nation_history/global_log) → 장기-시뮬
  Phase 4 "full-stored-bytes" 비교 활성화. golden-capturer + 실DB.

## 5. 리스크
- 이중날짜(백엔드-only 접두 시) — P2 에서 FE 동시 처리로만 해소.
- battle/EVENT/NOTICE formatType 오적용(날짜 없는 종류에 YEAR_MONTH) — §2 맵 엄수.
- mid-turn 월경계 year/month 출처 불일치(logger-push vs flush-stamp) — P1/P3 검증.
- 기존 테스트 다수가 접두-없음 문자열을 단언 중 — P2 에서 PHP-정답으로 일괄 교정(골든 완화와 구별:
  fix the impl/expectation to the oracle, 약화 아님). 단계별 fresh 재채점.

## 6. 참고 (file:line)
PHP: `ActionLogger.php`(23-39 const · 231-270 formatText · 119-215 push · 80-117 flush).
Kotlin: `TurnWorldModel.kt:123`(LogEntryDraft.format 미소비) · `DatabaseHooks.kt:480`(toLogRow verbatim)
· `JdbcFlushExecutor.kt:1464`(LogRow). FE: `world-log/page.tsx:95` · `history/page.tsx` · `admin7/page.tsx`
· `components/game/MyInfoLogPanel.tsx` · `lib/utilGame/formatLog.ts`. Legacy: `hwe/ts/PageHistory.vue:41`.
