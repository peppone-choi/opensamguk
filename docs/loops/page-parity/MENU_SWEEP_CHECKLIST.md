# 전 메뉴 패러티 sweep 체크리스트 (a107, 2026-06-14)

> 56 항목 = MainControlBar 21 + GlobalMenu 17 + route-only 18. devsam(라이브 :8080/sam/che) = grand truth.
> sweep: 역할별(군주/수뇌부/국가소속/재야) 로그인 → 각 항목 devsam↔opensamguk 대조 → 갭 루프.
> 진행표시: ☐미대조 / ✅대조완료 / 🔧루프진행 / ❌MISSING(생성필요).

## A. MainControlBar (21)
| 메뉴 | opensamguk | devsam | role | 상태 |
|---|---|---|---|---|
| 회의실 | /game/board | b_secret(일반) | 국가소속 | ☐ |
| 기밀실 | /game/board?secret=1 | b_secret(secret) | 수뇌부 | ☐ |
| 부대편성 | /game/troop | b_troop | 국가소속 | ☐ |
| 외교부 | /game/diplomacy | j_diplomacy | 수뇌부 | ☐ |
| 인사부 | /game/my-boss | b_employ | 국가소속 | ☐(군주 통째 fabricated 의심) |
| 내무부 | /game/nation-finance | 내정 | 수뇌부 | 🔧 **P0 크래시('city')** |
| 사령부 | /game/chief-center | b_military | 수뇌부 | 🔧 권한게이트(role_chief 재바인딩중) |
| NPC정책 | /game/npc-control | b_npcControl | 수뇌부 | ☐ |
| 암행부 | /game/generals?secret=1 | b_secret(암행) | 수뇌부 | ☐(권한뷰=전술컬럼) |
| 토너먼트 | /game/tournament | v_tournament | 전체 | ☐ |
| 세력정보 | /game/my-nation | v_myKingdom | 국가소속 | 🔧 **P0 크래시('toUpperCase')** |
| 세력도시 | /game/my-cities | v_cityList | 국가소속 | ☐ |
| 세력장수 | /game/generals | a_genList(내세력) | 국가소속 | ✅부분(loop31 공개컬럼) |
| 중원정보 | /game/global-diplomacy | a_kingdomList | 전체 | ☐(분쟁도시 cityConst 백로그) |
| 현재도시 | /game/city | v_cityInfo | 전체 | ☐ |
| 감찰부 | /game/coming-soon | b_inspect | 수뇌부 | ❌ coming-soon |
| 유산관리 | /game/inherit | v_inherit | 전체 | ☐ |
| 내정보&설정 | /game | v_myInfo | 전체 | ☐ |
| 경매장 | /game/auction | v_auction | 전체 | ☐(이전경매20 백로그) |
| 유니크경매 | /game/auction?type=unique | v_auction?unique | 전체 | ☐ |
| 베팅장 | /game/betting | v_betting | 전체 | ✅부분(loop36 reverse) |

## B. GlobalMenu (17)
| 메뉴 | opensamguk | role | 상태 |
|---|---|---|---|
| 천통국베팅 | v_nationBetting | 전체 | ❌ **MISSING(전용페이지 없음)** |
| 게임정보>세력일람 | a_kingdomList | 전체 | ☐ |
| 게임정보>장수일람 | a_genList | 전체 | ☐ |
| 게임정보>명장일람 | a_bestGeneral | 전체 | ❌ **MISSING** |
| 게임정보>명예의전당 | a_hallOfFame | 전체 | ❌ **MISSING** |
| 게임정보>왕조일람 | a_emperior | 전체 | ❌ **MISSING** |
| 연감 | /game/history | 전체 | ✅부분(loop33 색상) |
| 게시판(+건의/팁/패치) | /board/* | 전체 | ☐(외부URL) |
| 공식/잡담 오픈톡 | kakao | 전체 | ✅external |
| 전투시뮬레이터 | /game/simulator | 전체 | ☐ |
| 기타정보>접속량정보 | a_traffic | 전체 | ❌ **MISSING** |
| 기타정보>빙의일람 | a_npcList | 전체(npcMode) | ☐ |
| 설문조사 | /game/vote | 전체 | ☐ |

## C. route-only (18): admin1/2/5/7/8·tournament-admin(관리자, 저우선), join(재야)·mailbox(✅loop30)·map(✅loop38)·nation·rankings·select-pool·world-log·my-generals·history·simulator·vote·coming-soon(stub)

## 우선 갭 (sweep 무관 확정)
- ❌ MISSING 6: 천통국베팅·명장일람·명예의전당·왕조일람·접속량정보·감찰부 → 페이지 생성 루프
- 🔧 P0 크래시 2: my-nation(toUpperCase)·nation-finance(city) → afac06 null필드 진단중
- 정보 미매핑("-" 필드): 세율/지급률/국력/기술력/쿨다운/수비설정/벌점/주민현재값 (유저: 정보부족=갭) → 백엔드 데이터 파이프라인
