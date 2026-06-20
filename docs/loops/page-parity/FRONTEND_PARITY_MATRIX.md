# 프론트 패러티 라이브 대조 매트릭스 (devsam ↔ opensamguk)

> 목적: 공식 devsam(hwe/ts Vue, 라이브 :8080)을 grand truth로, opensamguk web/game을 역할별 페이지 단위로 대조.
> 셋업: 양측 동일 시나리오(scenario_1010) + 4역할 계정. devsam=legacy 정본, PHP 이김.
> 대조법: 역할 로그인 → 페이지 진입 → 레이아웃/섹션/필드/순서/컴포넌트 비교 → 차이를 루프 1개로 분해.
> 상태: 초안 — devsam URL/계정 a0b6 리포트 후 채움.

## 4역할 (권한 게이트 — 뷰 상이)

| 역할 | devsam 조건 | opensamguk 조건 | 전용 페이지 핵심 |
|---|---|---|---|
| 군주 | officer_level=군주(최고), nation 보유 | 동일 | my-boss(임명/추방/외교권자), nation(천도/유산), diplomacy(결정), global-diplomacy |
| 수뇌부 | officer_level 중상(참모/태수 등) | 동일 | chief-center(국가 예약명령), nation-finance, my-cities(인사부 일부) |
| 국가소속 일반 | nation_id≠0, 일반 officer | 동일 | my-nation, troop, diplomacy(열람) |
| 재야 | nation_id=0 | 동일 | join(임관/등용수락), select-pool, 제한 메인 |

## 페이지 대조 목록 (devsam hwe ↔ opensamguk route)

### 공통 (전 역할 로그인 시)
| 페이지 | devsam(hwe) | opensamguk | 감사 알려진 갭(a85c) |
|---|---|---|---|
| 메인 | PageFront.vue / index.php | /game | RecordZone 3컬럼 피드 무, info카드 순서 역전(City→Nation→General), MessagePanel 무 |
| 전체장수 | a_genList.php | /game/generals | ✅바퀴31 6컬럼 복원(얼굴/연령/성격/특기/관직/삭턴). refresh-score(벌점) DTO부재 |
| 내 장수들 | b_myGenInfo.php | /game/my-generals | 64px 초상 미렌더, isunited 소유자명 무 |
| 내 도시들 | b_myCityInfo.php | /game/my-cities | 정렬=패러티(바퀴 prep 확인). 암행부/인사부 mutation 잔여 |
| 도시 | b_currentCity.php | /game/city | id<=0 fallback(바퀴11), defence_train read-chain 미배선(P0-14) |
| 지도 | j_map / PageFront | /game/map | city.state flush 미구현, 최근맵 피드 무. ⚠️MapViewer↔MapPreview 동일 불변식 |
| 연감 | b_history.php | /game/history | ✅바퀴33 isBrightColor. 월별로그 writer 미구현(P0-20), 과거맵=현재맵 렌더 |
| 월드로그 | (발명) | /game/world-log | 의도적 divergence OK |
| 베팅 | PageNationBetting.vue | /game/betting | ✅바퀴36 reverse. PlaceBetHandler 부수효과(P0-07, 바퀴20 닫힘) |
| 경매 | v_auction.php | /game/auction | 이전경매20 미구현, 유니크경매(바퀴24 닫힘) |
| 게시판 | b_board.php | /game/board | 회의실/기밀실 토글 단일, author_icon 64px 무 |
| 투표 | PageVote.vue | /game/vote | 보상문구 치환 미검증 |

### 재야
| join | b_addGeneral.php | /game/join | 유산포인트 사용블록 무(P0-29), 전콘 pic no-op(P0-30), 임관권유문 무(P0-31) |
| 장수풀 | (select) | /game/select-pool | 대조 필요 |

### 국가소속 일반
| 내 국가 | b_myKingdomInfo.php | /game/my-nation | 셀배치 드리프트(8열→6열), setBlockWar 100% deny(nation_env read) |
| 부대 | PageTroop.vue | /game/troop | 도시명 대신 cityId, 부대장 아이콘 무 |
| 외교(열람) | j_diplomacy*.php | /game/diplomacy | 마스킹(바퀴18/22), 승인거부 무(P0-16) |

### 수뇌부
| 사령부 | b_reserveCommand.php | /game/chief-center | 당기기/미루기/반복(바퀴16), 고급모드 다중턴 일괄(P0-11) 무 |
| 국가재정 | v_nationStratFinan.php | /game/nation-finance | DTO shape 크래시(P0-51), income 위조(P0-52), 외교관계 섹션 무 |

### 군주
| 인사부 | b_myBossInfo.php | /game/my-boss | 페이지 통째 fabricated(P0-37~43): 로스터+수뇌임명+도시관직+추방+외교권자 |
| 국가정보 | b_myKingdomInfo.php(군주 액션) | /game/nation | 유산구매 UI 오배치(inherit 전용인데 nation에), 버프 라벨 날조 |
| 외교(결정) | j_diplomacy*.php | /game/diplomacy | 승인/거부 end-to-end 무(P0-16) |
| 중원외교 | PageGlobalDiplomacy.vue | /game/global-diplomacy | 분쟁도시 id→name(바퀴35 차단, cityConst 로더 필요), 국가표 장수컬럼 무 |

### MISSING (라우트 자체 없음)
| 전투기록실 | v_battleCenter.php | (없음) | 신설 필요 P0 |
| 토너먼트 | b_tournament.php | (없음) | 신설 필요 P0 |

## 라이브 대조 결과 — 군주 (a3705, 2026-06-14, devsam che ↔ opensamguk 로컬)

> ⚠️ 셋업 결함: role_lord general 미바인딩(null)→/game/my-nation·nation 크래시. a071이 교정 중.
> 아래 **[S]=구조적(데이터 무관, 확정)** / **[D]=데이터의존(깨끗한 재대조 필요)**.

- **[S] P0 메인 메시지 패널 외교 탭 누락** — devsam 4채널(전체/국가/개인/외교), opensamguk 3채널. + 서신전송 비활성("API 미지원") — loop30이 mailbox엔 추가됐으나 메인 패널은 별개/구빌드 가능성
- ~~**[S] P0 메인 접속국가·접속자 섹션 없음** — devsam "접속중 국가/【접속자】" 2줄~~ **닫힘 바퀴63**
- ~~**[S] P1 메인 국가방침 섹션 없음**~~ **닫힘 바퀴63**
- **[S] P1 개인 명령목록 당기기/미루기/고급모드/반복 버튼 없음** — devsam 메인 명령목록 상/하단 버튼군 (chief-center엔 loop16 추가됐으나 개인 명령목록엔 없음)
- **[S] P1 감찰부 coming-soon** — devsam v_battleCenter 실기능 vs opensamguk placeholder
- **[S] P1 맵 페이지 중원정세 로그 없음** — devsam v_cachedMap 하단 4줄
- **[S] P1 글로벌메뉴 레이블** — devsam "기타정보" vs opensamguk "접속량정보"
- **[D] P0 /game/my-nation·/game/nation 크래시** — null general deref(셋업 바인딩 이슈 + 방어 부재). a071 바인딩 후 재확인; 방어적 null처리는 WS-C 후보
- **[D] 장수목록 비교는 매핑 오류** — devsam b_genList.php=**암행부(권한뷰)** ≠ opensamguk /game/generals(공개). opensamguk 암행부 등가 페이지 별도 확인 필요(공개목록은 loop31로 패러티)
- 스크린샷: tmp/parity-shots/lord/ (devsam-*.png / osam-*.png)

### 군주 갭 소스 스코핑 verdict (a73b)
- **GAP4 당기기/미루기/반복 = 갭 아님(정정)** — PartialReservedCommand.tsx 이미 구현+GameChrome 마운트. 고급모드=CommandModal 의도적 divergence
- **GAP6 맵 중원정세 로그 = CHEAP 프론트** — WorldLogController 존재 → fetch+formatLog 재사용. **루프38 진행**
- **GAP1 외교 메시지 탭** — 탭추가 1줄 cheap, BUT 상대국 mailbox contact-list API 부재 → 선택기 COUPLED. read-only 고정-mailbox 탭만 cheap
- **GAP5 "기타정보" 레이블** — GlobalMenu 서버드리븐(game-api GetMenu item.name) or rankings/page.tsx:14 로컬 label. 저가치, 출처 확인 필요
- **GAP2 접속국가/접속자 + GAP3 국가방침 = BACKEND-COUPLED(고가치 파이프라인)** — 데몬이 online_nation/online_user_cnt/onlineGen/nationNotice KV를 world_state.config/nation_env에 미write([§2 BLOCKED]). IdentityDto 필드·FrontInfoController read 코드는 이미 존재→데몬 KV write만 추가하면 언블록. **별도 WS-C 백엔드 루프(엔진 KV write→game-api 언블록→프론트 렌더). Docker IT 필요**
- **핵심 결론: opensamguk 프론트 *레이아웃* 골격은 대체로 충실. 남은 갭 = 주로 (a) 데몬 KV 파이프라인 미구현 → 정보 미표시 (b) 미구현 페이지(암행부/battle-center/tournament).**
- **⚠️ 유저 확정(2026-06-14): "정보가 부족하면 그건 갭이다."** — backend-coupled(데몬 KV)는 후순위 아님. devsam이 표시하는 정보를 opensamguk이 못 표시하면 **모두 실 파러티 갭 = 고침 대상**. [§2 BLOCKED] DTO 필드 전수(onlineGen/onlineNations/onlineUserCnt/nationNotice + 기타) = 데몬 KV write 파이프라인으로 언블록 = WS-C 백엔드 루프 패밀리(고가치).

## 라이브 대조 결과 — 수뇌부 (ab5b, devsam chief=공융 lvl11 ↔ opensamguk role_chief=관우 lvl3)

> ⚠️ 셋업 이슈: role_chief lvl3(perm1 관직자) = 수뇌부 자격 미달 → chief-center 차단. **lvl≥5 재바인딩 후 재대조 필요**.

- **[S] P0 nation-finance 크래시** — `Cannot read properties of undefined (reading 'city')`. audit P0-51 라이브 확정. /game/nation-finance 전체 렌더 불가
- **[S] P0 my-nation 크래시** — `reading 'toUpperCase'` (null 문자열 필드, 성향/type 등). a071 HTTP-200은 셸만, client JS 크래시. role_chief(관우)에서 발생
- **[P0 셋업] chief-center 권한 차단** — opensamguk 수뇌부 판정 임계 vs role_chief lvl3. 재바인딩(lvl≥5) 후 사령부 그리드 대조
- **[정보갭 — 유저 "정보부족=갭"] 국가패널 "-"** — 세율/지급률/국력/기술력/전략·외교쿨다운 미매핑(API null)
- **[정보갭] 장수패널 수비설정/실행잔여/벌점 누락**
- **[D] 주민 현재값=최대값** — devsam 3M/4.35M(진행) vs opensamguk 4.35M/4.35M(초기). ProcessIncome 미적용 or 로컬 stale(park: stale DB)
- **[S/P1] 국가메뉴 7항목 비활성** — 기밀실/외교부/내무부/사령부/NPC정책/암행부/감찰부 (devsam 수뇌부 전부 active). 일부 coming-soon, 일부 권한게이트
- 메인 중원정세 로그(loop38) / 외교탭(GAP1) / 기타정보 레이블(GAP5) — 군주와 공통
- 스크린샷: parity-shots/chief/

## 대조 후 루프 분해 원칙
- 페이지 1개 차이 = 루프 1개(큰 페이지=섹션별 분해). devsam 라이브 렌더가 oracle.
- mutation 경로는 intake 등록 증명 필수(바퀴30 교훈). 읽기 갭부터.
- 두 맵뷰어 동일 불변식 유지(하나 고치면 둘 다 + 양쪽 tsc).
