# PHP ↔ 코드베이스 갭 스냅샷 — 2026-06-12 (main 5e4e25e 기준)

3축(페이지/API/턴명령) 병렬 감사 + 현행 코드 실측(intakeCodes·컨트롤러 grep) 교차검증 결과.
**구문서(API_GAP.md 등)의 MISSING 다수가 이미 닫혀 있어 실측으로 교정했다** — 이 문서가 현재 시점 정본.

## 1. 턴 명령 (Command/General 55 + Command/Nation 38 = 고유 92)

| 판정 | 수 |
|---|---|
| OK (CommandRegistry 등록 + 실구현) | 90 |
| design-deferred STUB | 2 (`che_견문`, `che_인재탐색` — F-BRIDGE 다운스트림 seam, PARITY_LEDGER DONE. 단 LEDGER 백로그에 silent no-op 턴소진 주의 등재) |
| **MISSING** | **0** |

## 2. API (sammo/API 79 클래스 + j_*.php)

### 실측으로 닫힘 확인 (구감사 stale 항목)
auctionBid/auctionOpen 3종 · vote 4종(newVote/voteCast/voteComment/voteClose) ·
sendMessage/deleteMessage/ContactController · setScoutMsg 등 nation setter 6종 ·
troop 5종 · board 2종 · diploSendLetter/Rollback/Destroy · selectPoolPick/Update ·
inheritResetTurnTime/ResetSpecialWar/SetNextSpecialWar/BuyHiddenBuff/BuyRandomUnique ·
command bulk/push/repeat(+nation 변형, CommandController) ·
instant-action(DieOnPrestart/DropItem/InstantRetreat/ResetStat/CheckOwner — InstantActionController) ·
Nation/GetGeneralLog(바퀴 1).

### 진짜 MISSING (확정)
| API | 비고 |
|---|---|
| Misc/UploadImage | 장수 초상 업로드 — repo 무흔적 |
| Global/GetRecentRecord | j_map_recent — 최근 지도 스냅샷 피드 |
| Global/GeneralListWithToken | NPC 선택 풀 토큰 read (mutation 짝 selectPool*은 존재) |
| InheritAction/GetMoreLog | 유산 로그 페이징 read |
| Admin/BanEmailAddress | 어드민 |
| Global/ExecuteEngine | 어드민 데몬 트리거 — 의도적 divergence 후보 |
| Login/ReqNonce | 자체 JWT 인증으로 의도적 divergence (F0) |

### 잔여 PARTIAL 주의
- instant-action 코드 일부 "배선되지 않은 즉시 액션" 분기 잔존 가능(LEDGER 백로그: ResetStat 409) — 코드별 실호출 검증 필요.
- read API 필드/페이징 부분합치(GetMap 연월 파라미터, 메시지 unread 마킹 등) — PAGE_PARITY 감사의 P1 군.

## 3. 페이지 (hwe/*.php + Vue ↔ web/game)

| 판정 | 수 | 내용 |
|---|---|---|
| OK | 17 | 랭킹 8 + 어드민 5 + global-diplomacy/npc-control/select-pool/simulator |
| PARTIAL | 16 | PAGE_PARITY_AUDIT_2026-06-10 의 P0/P1 군과 일치 (my-boss 조직도, chief-center 고급모드, inherit 사용블록, join 유산/초상/임관문, city 방어컬럼, history 월별로그, nation-finance 외교표, 메인 RecordZone 등) |
| **MISSING (라우트 무)** | **2** | **`v_battleCenter.php`(전투기록실)** · **`b_tournament.php`(토너먼트 16강 베팅)** — 둘 다 기존 백로그 등재(battle-center=LEDGER, tournament=P0-05) |
| 검증 미흡 | 2 | /game/vote, /game/troop — 라우트 존재하나 PHP 대비 기능 검증 미실시 |
| placeholder | 1 | v_processing → /game/coming-soon |

## 4. 결론 — 빠진 것 요약

1. **페이지 2개 라우트 자체 부재**: battle-center, tournament(16강 베팅) — 최우선 신규 페이지 후보.
2. **read API 4종**: GetRecentRecord / GeneralListWithToken / GetMoreLog / (GetGeneralLog alias self-view 변형).
3. **UploadImage** 1종 (mutation).
4. **턴 명령 갭 0** — 92/92 등록.
5. 나머지는 "있는데 부분"(PARTIAL 16페이지 + read 필드 합치) — 기존 PAGE_PARITY/LEDGER 백로그 체계로 바퀴 단위 마감이 정석.
6. 어드민/인증 2종(ExecuteEngine, ReqNonce)은 의도적 divergence로 분류 권고.
