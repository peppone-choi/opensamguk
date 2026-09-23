# 휘하 시야·첩보 계약

> 작성일: 2026-09-23
> 상태: **구현 계약(수치 2026-09-23 확정).** 이슈 #785(OPENSAM-265) · #343(OPENSAM-166) · #465(OPENSAM-205)의 서버 쪽 범위.
> 상위: [장수·휘하 캠페인 재설계](./2026-09-17-general-and-retinue-campaign-redesign.md) §5.1(7단계), §5.1.1 (D), §6.4, §7, §12.1, §14 · [입력 registry 계약](./2026-09-17-input-registry-contract.md)

## 1. 사용자 결정(2026-09-23)

- 시야 단위는 **郡國**이다. 번호 `no` 는 선택된 세계 판의 han-tiles `parentRegions`(= `juns`) 색인이며, `/api/map/provinces` PNG 의 commandery 채널과 같은 번호다(`tools/map/build_province_map.py` 가 `parentOwner` 로 굽는다).
- 단계는 `FULL` / `INTEL` / `FOG` 세 가지다.
- 첩보 결과는 **그 순간의 스냅숏**이고 「N순 전」을 함께 보인다. 갱신은 다시 첩보할 때뿐이다(만료 없음).
- FULL 의 출처: 자기 위치, 자기 군단 위치, 자기 縣(영토)·휘하 배치, 정찰 배치, 망루·봉화 공사. 반경·구간·비용은 확정 데이터 파일에 둔다.
- 옛 웹 규칙 「내 郡國 + 8방향 이웃 = 완전 시야」는 SAMMO 매뉴얼에서 온 것이며 §7 이 아니다. **채택하지 않았다.** 기본 시야 반경은 0(자기 郡國만)이고, 이웃은 직접 행동 첩보나 정찰 배치·망루봉화로만 본다.

## 2. 확정 수치 — `data/curated/han/hwiha-vision-rules-v1.json`

코드에 사본을 두지 않는다. 로더(`HwihaVisionRules`)가 스키마를 엄격히 읽고 어긋나면 실패한다.

| 항목 | 값 | 비고 |
|---|---|---|
| 인접 규칙 | `SHARED_BORDER_4_NEIGHBOUR` | owner 래스터 상하좌우 경계 공유. tiles `adjacency.commandery` 와 같아야 한다(로더가 교차 검사) |
| 반경 SELF / OWN_CORPS / RETINUE / TERRITORY | 0 / 0 / 0 / 0 | 걸음 = 郡國 인접 그래프 한 칸 |
| 반경 SCOUT_POST / WATCHTOWER_BEACON | 1 / 1 | |
| 병력 구간 | B1 0–999 · B2 1000–4999 · B3 5000–9999 · B4 10000–29999 · B5 30000+ | 남의 군단은 구간만 |
| 첩보 비용 | 전·곡·철·목재·말 모두 0 | 차감 경로(보급망 접근·창고 revision 결속)가 없으므로 0 이 아니면 로더가 거절한다 |

## 3. 시야 출처와 저장 키

| 출처 | 읽는 곳 | 비고 |
|---|---|---|
| SELF | `general_spatial_position`(본인) | 육지 省일 때만 |
| OWN_CORPS | 본인이 소유한 출전 군단(`hwihaDeployment`)의 지휘 장수 위치 | 수역에 있으면 郡國 없음 |
| RETINUE | 본인 휘하 카드(`general_retainer.master_general_id = 본인`, `general_id` 있음)의 위치 | 발령 부임지(`hwihaCountyAssignment`)는 자기 세력 縣이라 TERRITORY 가 덮는다 |
| TERRITORY | `province_control.nation_id = 본인 세력`(세력 0 은 없음) | 省 하나라도 있으면 그 郡國 전체 |
| SCOUT_POST | 본인 장수 meta **`hwihaScoutPosts`** | 아래 스키마. 내정 입력 흐름이 기록한다 |
| WATCHTOWER_BEACON | 자기 세력 縣治 城 meta **`hwihaCountyWorks`** | 아래 스키마. 공사 입력 흐름이 기록한다 |

**내정 입력(배치·공사) 흐름이 쓸 저장 키 — 이 계약이 기대하는 모양이다.** 둘 다 없으면 시야 기여가 없다(기본값 없음).

```text
general.meta.hwihaScoutPosts = {"version":1,"posts":[{"retainerId":<int>0>,"provinceId":"<pinned land province id>","status":"ACTIVE"|<그 밖>}]}
  - ACTIVE(카드가 실제로 도착) 만 시야가 된다. 다른 status 는 무시(오류 아님).
  - retainerId 는 그 장수 자신의 휘하 카드여야 한다(아니면 무효로 센다).
city.meta.hwihaCountyWorks = {"version":1,"works":[{"kind":"WATCHTOWER_BEACON","status":"COMPLETE", ...}]}
  - kind == WATCHTOWER_BEACON && status == COMPLETE 만 읽는다. 다른 kind·status·추가 필드는 공사 흐름 소유.
```

잘못된 행은 시야로 바뀌지 않고(지어낸 시야 금지), 투영 전체를 막지도 않는다(한 행이 모두를 눈멀게 하지 않는다). 수는 응답의 `invalidSourceRecords` 로 드러난다. 접근 인터페이스는 `HwihaVisionSourceReader`, 기본 구현은 `HwihaMetaVisionSourceReader`(`logic/.../input/HwihaVisionSources.kt`).

**세력 공유:** §7 에 시야 공유 규칙이 없으므로 장수별 투영만 한다. 영토(TERRITORY)는 세력 소유이므로 같은 세력 장수가 같은 영토 시야를 갖는 것은 공유가 아니라 출처가 같은 것이다. 첩보 기록도 본인에게만 남는다.

## 4. 조회 API(읽기 전용)

관문은 `HwihaCampController` 와 같다: principal 없음·범위 밖 401, `?generalId=` 가 본인 장수가 아니면 403, 휘하 규칙이 아닌 세계는 200 + `WRONG_RULE_PROFILE`, 권위 상태 손상은 200 + `UNAVAILABLE`(부분 지도 없음). 응답은 `Cache-Control: no-store`. **null 필드는 직렬화에서 빠진다** — 볼 자격이 없는 값은 `null` 이 아니라 키째로 없다.

```jsonc
// GET /api/hwiha/visibility?generalId=
{ "status":"READY", "stamp":{"year":190,"month":3,"phase":2},
  "commanderies":[ {"no":0,"id":"PARENT-0000","name":"하남윤","tier":"FULL"},
                   {"no":5,"id":"PARENT-0005","name":"…","tier":"INTEL","seenAtStamp":{"year":190,"month":2,"phase":1},"ageTurns":4},
                   {"no":7,"id":"PARENT-0007","name":"…","tier":"FOG"} ],   // 판의 모든 郡國
  "sources":[ {"kind":"SELF","commanderyNo":0,"radius":0,"provinceId":"82828","refId":1},
              {"kind":"TERRITORY","commanderyNo":0,"radius":0} ],
  "invalidSourceRecords":0 }

// GET /api/hwiha/corps?generalId=
{ "status":"READY", "stamp":{…},
  "corps":[ {"corpsId":"<내 출병 requestId>","ownerGeneralId":1,"ownerName":"…","commanderGeneralId":1,"commanderName":"…",
             "nationId":1,"nationColor":"#…","provinceId":"…","commanderyNo":0,"visibility":"FULL","own":true,
             "troops":1234,"marchPath":["<현재 省>","…","<목적 省>"],"destinationProvinceId":"…"},
            {"corpsId":"<16hex 불투명 키>","ownerGeneralId":2,…,"visibility":"FULL","own":false,
             "troopsBand":{"code":"B3","label":"5천~1만"}},
            {"corpsId":"<16hex>","ownerGeneralId":4,…,"visibility":"INTEL","own":false,
             "troopsBand":{"code":"B1","label":"1천 미만"},"lastSeenStamp":{…},"ageTurns":4} ] }

// GET /api/hwiha/scout-options?generalId=
{ "status":"READY","inputId":"action.scout","available":true,
  "origin":{"provinceId":"…","commanderyNo":0,"id":"PARENT-0000","name":"하남윤"},
  "cost":{"money":0,"grain":0,"iron":0,"timber":0,"horses":0},
  "options":[ {"no":1,"id":"PARENT-0001","name":"하내군","tier":"FOG","available":true},
              {"no":3,"id":"PARENT-0003","name":"홍농군","tier":"INTEL","available":true,"seenAtStamp":{…},"ageTurns":2} ] }
// 위치가 육지 省이 아니면 status READY, available false, code POSITION_UNAVAILABLE, options 없음.
```

- `corpsId`: 자기 군단은 출병 requestId(= `hwihaDeployment.orderId`, 출병 옵션의 `order.orderId` 와 같다), 남의 군단은 `sha256("hwiha-corps:"+orderId)` 앞 16자. 남의 requestId 는 내보내지 않는다.
- `troops` 는 자기 군단만, `troopsBand` 는 남의 군단만. `marchPath`·`destinationProvinceId` 는 자기 군단만.
- `interceptRange` 는 **내보내지 않는다** — 요격 방침이 아직 없다(`HwihaMarchReactions` 는 빈 목록만 지원).

## 5. 군단 투영 규칙(#343 · #465)

- 살아 있는 군단은 (가) 자기 군단, (나) FULL 郡國에 선 군단만 나온다. (나)는 관계 재검사(`HwihaDeploymentRules.assessActive`)가 통과한 것만.
- INTEL 郡國에서는 **그 郡國 첩보 스냅숏의 군단만** 나온다(규칙이 허용한 마지막 목격 = 첩보 기록). 지금 그곳에 선 군단은 나오지 않는다.
- FOG 郡國의 군단은 DTO 로 만들지 않는다 — 직렬화 바이트에 이름·id·키·병력·省이 없다. 이 성질은 `HwihaVisionReaderTest` 가 같은 응답 안의 FULL 군단(양성 대조)과 함께 검증한다.
- 지나가며 본 군단의 수동 목격은 기록하지 않는다(매 턴 모든 장수 쓰기가 필요). 목격 기록은 첩보뿐이다.

## 6. 직접 행동 `action.scout`(§12.1)

- 원장 `data/commands/hwiha-input-catalog.json` 에 `GENERAL_ACTION`, layer 1, `HANDLER_READY`, `legacyCommands: ["che_첩보"]` 로 올렸다.
- 인자: 정확히 `{"commanderyId":"PARENT-xxxx"}`(번호가 아니라 `parentRegions` id — 판 재배열에도 안정). 중복·여분 키·숫자형은 거절.
- 대상: 지금 서 있는 郡國과 경계를 맞댄 郡國 하나. 자기 郡國은 이미 보이므로 `NOT_ADJACENT`.
- 사유 집합(접수 = 실행 재검사): `WRONG_RULE_PROFILE`, `INVALID_INPUT`, `POSITION_UNAVAILABLE`, `UNKNOWN_COMMANDERY`, `NOT_ADJACENT`, `STATE_UNAVAILABLE`, (실행 때) `FORBIDDEN`. 접수는 출병과 같이 **현재 위치 기준으로 엄격히** 본다.
- 실행: §5.1 7단계. 첩보를 예약한 턴에는 자동 이동 단계가 돌지 않으므로(`HwihaAssignmentMarchTurn` — 현장 행동과 자동 이동을 겹치지 않는다) 실행 위치가 그 턴의 위치다.
- 기록: 본인 장수 meta **`hwihaScoutReports`** = `{"version":1,"tilesContentHash":…,"reports":[{commanderyId, seenAt{year,month,phase}, cities[{cityId,nationId,warehouse}], corps[{corpsKey,ownerGeneralId,commanderGeneralId,nationId,provinceId,troopsBand}]}]}`. 郡國마다 한 건, 다시 첩보하면 그 郡國만 교체. 정확한 병력·남의 requestId 는 저장하지 않는다. 다른 tiles 해시의 기록은 쓰지 않고(다음 첩보 때 새 공책으로 교체), 손상된 기록은 덮어쓰지 않고 `STATE_UNAVAILABLE` 로 거절한다. 저장은 `ChangeRecorder → JdbcFlushExecutor` 개인 턴 flush 하나다.
- 공개 범위: 카탈로그 「첩보」 행의 병력·창고 유무를 싣는다. 설치 계책 유무는 모델이 없어 싣지 않는다.

## 7. 아직 아닌 것

- 「보이지 않는 군단은 요격할 수 없다」 — 요격 방침이 없어 판정 지점이 없다. 요격을 만들 때 이 투영(`HwihaVision.project` → `tierOf`)을 써야 한다.
- NPC(§14) — AI 가 이 투영을 거치게 하는 배선은 없다. NPC 선택기는 현재 시야 밖 군단을 읽지 않지만(출사·발령만 함) 앞으로 군사 AI 는 이 경계를 써야 한다.
- 조우 전투 봉인 배치에서의 적 배치 가시성(§5.1.1 (D)), 설치 계책·대응 카드의 공개 전 비공개 투영, turnCompleted SSE·지난 순 로그의 시야 필터 — #343 의 나머지 범위.
- 세력 단위 시야 공유 — 설계에 규칙이 없다(미결).
- 첩보 비용의 실제 차감, 계책 카드 「첩보」(원격판).
