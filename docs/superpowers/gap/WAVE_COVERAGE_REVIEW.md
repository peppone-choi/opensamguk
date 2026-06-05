# WAVE_COVERAGE_REVIEW — W0b–W9 실행 스펙 completeness 크리틱 결과

> 출처: `parity-wave-specs` 워크플로(2026-06-05, 13 agents). 웨이브당 1에이전트가
> `gap/waves/WAVE_<id>.md` 작성(10개, **총 133 태스크**) → API/FE/command+logic 3차원
> completeness 크리틱이 GAP_AUDIT 인벤토리와 대조. **세 차원 모두 verdict=GAPS** — 스펙은
> 작성됐으나 아래 항목이 어느 웨이브 coveredItems에도 배정 안 됨(orphan). 정련 패스에서 닫을 것.
>
> ⚠️ 스펙 파일(`gap/waves/WAVE_*.md`)은 워크플로 중 git 충돌(W9 에이전트가 같은 worktree에서
> 브랜치 체크아웃+커밋)로 **로컬 `w9-public-read-endpoints`(4a4bb2a)에 보존**, main 미반영.
> 정식 산출은 specs를 main으로 분리 + W9 구현은 별도 리뷰 PR.

## 작성된 스펙 (10 waves / 133 tasks)

| Wave | tasks | golden | parallel groups |
|---|---:|:--:|---:|
| W0b founding sibling cascade | 5 | N | 1 |
| W1 daemon-seam correctness | 13 | Y | 3 |
| W2 silent-no-op intake | 8 | N | 3 |
| W3 read-DTO foundation | 15 | N | 5 |
| W4 read-page output parity | 17 | N | 5 |
| W5 mutation surface | 10 | N | 3 |
| W6 domain REST + new-player | 15 | Y | 6 |
| W7 per-command port tail | 22 | Y | 4 |
| W8 tournament engine | 22 | Y | 5 |
| W9 public reads + admin | 6 | N | 2 |

## API 차원 — orphan 엔드포인트 (어느 웨이브에도 미배정)

> (API 크리틱의 "WAVE 파일 없음" BLOCKER는 git race 오탐 — 파일은 실재. 단 아래 orphan은 진짜.)

- **NationCommand/ReserveBulk·Push·Repeat** (×3) — W6는 `Command/*` 3종만, NationCommand twin 누락 → W6e
- **General/GetGeneralLog + Nation/GetGeneralLog** (paged action/battle/history) — 어느 인벤토리에도 없음 → W3/W4 read 또는 W9
- **General/DieOnPrestart · DropItem · InstantRetreat** — 미배정 (DropItem/InstantRetreat은 PARITY_LEDGER 명령포트=W7 가능, DieOnPrestart prestart flow 미배정)
- **Nation/SetScoutMsg** (등용 메시지) — intake code 없음, 미배정
- **InheritAction/ResetStat·ResetTurnTime·ResetSpecialWar·SetNextSpecialWar** (×4) — 인벤토리는 BuyHiddenBuff/BuyRandomUnique만(W2/W5), Reset/Set 4종 미배정
- **Global/GetRecentRecord + GeneralListWithToken** — GetRecentRecord는 W3 DTO필드와 겹치나 REST route 미배정; GeneralListWithToken은 W6f와 모호
- **Diplomacy letters: j_diplomacy_{send,rollback,destroy}_letter** (×3) — W5d FE에만, API 웨이브 없음
- **j_board_article_add · j_board_comment_add** — board write 경로 미배정
- **j_set_my_setting · j_vacation · j_adjust_icon · Misc/UploadImage · Login/ReqNonce · Admin/BanEmailAddress** — long-tail 미배정 (j_install*/j_autoreset/j_raise_event/ExecuteEngine는 design-replaced=W9c 문서화)
- **~37 PARTIAL** (GetFrontInfo subset, GetCommandTable shape, GetNationInfo union, GeneralList columns, mailbox paging, auction/betting detail, GetMap/GetCachedMap, GetCurrentHistory) — 대부분 W3/W4 read-DTO로 의도되나 API 차원 coveredItems 미열거 + contract 종결 검증 스펙 없음

### double-assigned (의도적 레이어링이나 경계 미문서)
- Auction Bid*: W2a(casing fix) + W6c(domain REST path) — 어느 웨이브가 PRESENT 복원인지 미명시
- Auction Open*/Bid*: W6 내 REST+logic+engine+wire 레이어 분산(허용) — 단일 스펙 미연결
- NPC select-pool: 기존 j_select_npc claim 경로 vs W6f 트리오(token/picked/update) 경계 미획정

## FE 차원 — 7 누락 (GAP_AUDIT 배정됐으나 coveredItems 부재)

- **내정보 log/record 4섹션** (개인기록 action / 전투기록 battleDetail / 전투결과 battleResult / 장수열전 generalHistory, 각 24행 + '이전 로그') → **W4(4c)**
- **감찰부 battle center** (PageBattleCenter read) → **W4(4c)** (W8 8d는 simulator만)
- **입국 FE 페이지** (PageJoin/세력입국) → **W6(6d)** (W6은 General/Join BE만)
- **장수 선택풀 FE 페이지** (select_general_from_pool) → **W6(6d)** (BE만)
- **빙의 선택 FE 페이지** (select_npc) → **W6(6d)** (NpcSelectController BE만)
- **도시내 장수목록 + 예약명령 테이블** (b_currentCity) → **W4(4b city/page.tsx)** (W3 3a는 officer names·gauge만)
- **my-boss/인사부 enrichment** (b_myBossInfo 567줄 ~95% missing) → **W3 DTO + W4 렌더**

## command+logic 차원 — 1 누락

- **restart/rehydrate lossless 게이트** (LOGIC_GAP §15) — created nation flush→rehydrate 무손실 round-trip 게이트가 어느 웨이브에도 미배정 → **W0b 영속화 범위에 추가**

## Open Questions (54건, 정련 패스에서 결정)

핵심 패러티 결정(전수는 워크플로 산출 참조):
- **W1 dyingMessage** `array_rand` 비결정 → 고정/시드결정론 대체 정책(quarantine 문서화) — 사용자/PARITY_LEDGER 확인
- **W1 NextNPCRuler** PHP 연산자 우선순위 버그 `!$x == $y` → 1010 골든으로 버그-포함 byte-faithful 재현 여부 결정
- **W1 1c dex 합** PHP는 dex1~4(dex5 제외), Kotlin DEX_KEYS 5개 충돌 → PHP대로 4개
- **W2 betting amountMin** FE 501 하드코딩 vs PHP `rule('min','amount',10)` → 출처 확인
- **W2 BuyHiddenBuff** 로그 byte-parity = TriggerInheritBuff::BUFF_KEY_TEXT(8 한글명) 미추출 → legacy에서 추출 필요
- **W3 recentRecord/dex/header-gate 소스** — opensamguk 영속 테이블/컬럼 존재 확인 (GeneralRowMapper dex1~5/special/personal 등)
- **W4** 내정보 log 4섹션 + 감찰부 = general_record 테이블 의존 → 영속 소스 확정(최대 차단)
- **W5** chief 예약 turnIdx 의미 — 다중 turnList를 단일 turnIdx N회 vs ReserveBulk 확장
