# RTK 시스템 후보 카탈로그 — 웹 턴제 멀티플레이어

- 작성일·직접 접근일: 2026-07-17 (Asia/Seoul)
- 상태: `DONE_WITH_CONCERNS` — 8축 비교는 완료했으나 세부 수치·패치별 차이·재배포 권리는 일부 `UNKNOWN`이다.
- 실행 계약: [`2026-07-17-opensam-90-91-102-109-113-execution-contract.md` §7](../plans/2026-07-17-opensam-90-91-102-109-113-execution-contract.md)
- 제품 경계: [`2026-07-12-opensamguk-v2-product-spec.md` §2·4·6·9](../specs/2026-07-12-opensamguk-v2-product-spec.md)
- 시스템 백로그: [`2026-07-17-v2-ticket-backlog/04-systems-micro.md`](../plans/2026-07-17-v2-ticket-backlog/04-systems-micro.md)
- 입력 brief: `/tmp/opensam-wiki-source-brief.md`, SHA-256 `5330f2d494d39c112405797f6e980c2801d9644bba29a06e41bef87480d83f08`

### 조사 경계와 판정 언어

- `[사실]`은 이 문서가 직접 연 공식 매뉴얼·공식 시스템 페이지 또는 명시한 팬 위키 section에서 관찰한 게임 규칙이다. 게임 규칙은 역사 증거가 아니다.
- `[추론]`은 해당 규칙을 오픈삼국의 서버 권위·결정적 replay·예약 cadence에 대입한 해석이다. `[추천]`은 구현 승인이 아닌 A2 검토 후보이다.
- `ADOPT`는 의미를 유지해도 되지만 오픈삼국 공통 권위·로그 계약은 여전히 필요하다. `ADAPT`는 핵심 아이디어만 채택한다. `HOLD`는 직접 근거나 선행 계약이 부족하다. `REJECT`는 v2 정본과 충돌하는 형태를 채택하지 않는다.
- OPENSAM-96은 source/provenance 입력일 뿐 완료를 가정하지 않는다. OPENSAM-97·98·99·100도 완료를 가정하지 않는다. 특히 portrait/stat, stable key, CDN 또는 asset bundle이 준비됐다는 전제로 후보를 승격하지 않는다.
- 모든 후보는 v2 world/profile 아래 additive하게 격리한다. v1 PHP RNG·반올림·로그·`officer_level`·예약 결과는 바꾸지 않는다.

### evidence·rights 등급

| 등급 | 뜻 | 사용 경계 |
|---|---|---|
| `E-A` | 코에이테크모 공식 Web 매뉴얼 | 규칙 관찰의 1차 GAME_REFERENCE. 표시되지 않은 수치·패치 차이는 `UNKNOWN`. |
| `E-B` | 코에이테크모 공식 제품 시스템 페이지 | 공개된 핵심 규칙 확인용. 홍보 페이지이므로 예외·정확한 산식은 `UNKNOWN`. |
| `E-C` | 커뮤니티 WIKIWIKI | 낮은 등급의 발견·교차확인용. 댓글·공략 평가는 규칙 근거에서 제외. |
| `R3` | 공식 사이트, open license/재배포 허가 미관찰 | 링크·짧은 사실 요약만. 원문·표·이미지·asset 번들 금지. |
| `R4` | 팬 편집물과 게임 유래 정보가 혼재하고 downstream grant 미관찰 | research-only. 데이터·문장·이미지 수집/번들 금지. |

[코에이테크모 네트워크 이용약관 §14](https://www.gamecity.ne.jp/kiyaku/gc1.pl?kiyaku_only=1)은 서비스 정보의 권리 귀속과 무단 복제·재배포 제한을 명시한다. 이 약관이 각 공개 제품 페이지에 적용되는 정확한 범위는 `UNKNOWN`이지만, 어느 페이지에서도 open-data 또는 일반 재배포 허가는 관찰되지 않았다. [WIKIWIKI 이용약관 §6·§8](https://wikiwiki.jp/pp/policies)은 권리 침해·과부하를 금지하고 서비스/게시물 권리를 운영자·허락받은 자에게 두며, opensamguk에 대한 일반 이용허락은 관찰되지 않았다. 따라서 본 문서는 규칙을 손으로 요약한 비교 연구만 보존한다.

### normalized multiplayer 기준

| 필드 | 공통 의미 |
|---|---|
| `authority` | 최종 판정·시간·소유권은 서버가 가진다. 클라이언트는 intent만 제출한다. |
| `determinism` | snapshot/version/input/seed와 ordered state diff를 replay에 고정한다. 숨은 난수도 서버 seed와 draw log로 재현한다. |
| `cadence` | `즉시 intent → 개인턴/사령턴 resolve → 월간·시즌 settlement`, 전투만 별도 fixed tick을 쓴다. |
| `abuse` | alt 계정, 담합, 연속 spam, offline 타격, 정보 누출, 독점·dogpile을 최소 점검한다. |

## 외교

### `DIP-14-ALLIANCE` — RTK14 기간제 동맹·공격 요청

- `candidate_id`: `DIP-14-ALLIANCE`; 게임/버전: RTK14 공식 시스템 페이지(페이지가 base/PK 세부 버전을 표시하지 않아 patch는 `UNKNOWN`).
- rule summary: `[사실]` 친선은 금·군량·명품 제공으로 외교 감정을 올리고, 평상 이상이면 최장 24개월(72턴) 동맹을 맺는다. 동맹은 상호 비공격, 상대 영토 통과 중 병참 유지, 제3세력 거점 공격 요청을 허용한다. 공격 요청과 항복 권고에는 군주 작위 조건이 있다.
- exact evidence: [공식 `外交・計略` 페이지, `外 交`·`外交一覧`](https://www.gamecity.ne.jp/sangokushi14/system-strategy.html) (`E-B/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 명시적 만료와 비공격 상태는 비동기 멀티에 잘 맞지만, 일방적 공격 요청은 상대 플레이어의 agency를 지우면 안 된다. single-player dependency는 AI가 요청을 즉시 판정하고 영토 통과 병참을 자동 처리하는 부분이다.
- required adaptation: `[추천]` `DiplomaticContract`에 당사자, world-clock 만료, 통행권, 지원 의무, 보증금, 파기 notice/cooldown, 응답 deadline을 기록한다. 공격 요청은 상대의 수락·축소·지연 응답을 거쳐 `Operation`에 붙인다.
- authority/determinism/cadence/abuse: server-authoritative contract와 idempotency key; 동일 snapshot+제안+응답으로 동일 계약 hash; 사령턴 제출·시즌 settlement; 선물 spam, alt 호감 세탁, 통행권을 이용한 기습, 대세력 dogpile 위험.
- v1/v2: v1 외교 결과를 바꾸지 않고 v2 contract/log만 추가한다.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 정확한 성공 산식, 파기 페널티 전부, PK/patch 차이. v2 `FeudalContract`와 일반 동맹은 별도 타입이어야 한다.

### `DIP-8R-COALITION` — RTK8R 표적 연합·원군

- `candidate_id`: `DIP-8R-COALITION`; 게임/버전: RTK8 REMAKE 공식 Web 매뉴얼(표시 patch `UNKNOWN`).
- rule summary: `[사실]` 동맹은 상호 공격과 계략을 막고 원군 요청을 허용한다. 연합은 한 세력을 표적으로 여러 세력이 결성·가입하며, 회원끼리 출진/계략을 할 수 없고 표적에 대한 연합전은 모든 회원에게 원군을 요청한다. 탈퇴·파기는 관계와 부하 충성 저하 가능성을 가진다.
- exact evidence: [공식 매뉴얼 `評定コマンド` → `外交`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/5100.html) (`E-A/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 표적·회원·금지행위가 명시되어 멀티 계약으로 옮기기 쉽다. single-player dependency는 AI 회원 전체에 원군 요청을 자동 전파하고 응답을 대체하는 부분이다.
- required adaptation: `[추천]` 창설 vote, 가입/탈퇴 notice, 원군 obligation의 수량·도착 window, 기여도와 전후 종료 조건을 명시한다. 회원 전원 소집은 opt-in 또는 사전 계약 범위 안에서만 실행한다.
- authority/determinism/cadence/abuse: 서버가 회원/표적/금지행위/원군 예약의 정본; coalition version과 ordered votes를 replay; 시즌 외교 settlement와 Operation별 응답; 다계정 연합, 약소세력 집단 괴롭힘, 막판 탈퇴, 정보 공유 악용 위험.
- v1/v2: v2 `DiplomaticContract`/`Operation` additive. v1 동맹·전쟁 로그 불변.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 최대 회원 수, 연합 수명, AI 응답 산식, 동시 연합 conflict 규칙.

## 계략

### `STR-14-STATE-SABOTAGE` — RTK14 다섯 표적 상태 계략

- `candidate_id`: `STR-14-STATE-SABOTAGE`; 게임/버전: RTK14 공식 시스템 페이지, 세부 patch `UNKNOWN`.
- rule summary: `[사실]` `埋伏の毒`은 지역 개발·치안을 낮추고 도적/이민족 발생 가능성을 만들며, `離間`은 충성, `地域懐柔`는 지역府 지배권, `駆虎呑狼`은 도독/태수 독립, `二虎競食`은 두 세력의 외교 감정을 대상으로 한다. 실패하면 대상 세력과의 관계가 악화될 수 있다.
- exact evidence: [공식 `外交・計略` 페이지, `計 略`·`計略一覧`](https://www.gamecity.ne.jp/sangokushi14/system-strategy.html) (`E-B/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 서로 다른 canonical target/state diff로 정규화할 수 있다. single-player dependency는 offline 플레이어의 영토·장수를 즉시 빼앗는 결과다.
- required adaptation: `[추천]` intel freshness, 사령 권한, 비용 escrow, prepare→detect/counter→resolve 상태기계, 최소 warning window를 둔다. 독립·지배권 이전은 즉시 boolean flip 대신 claim/불만/반란 operation을 생성한다.
- authority/determinism/cadence/abuse: 서버가 비밀 intent·visibility·seed를 소유; input feature와 draw/ordered diff를 관리자 replay에 보존; 사령턴 준비 후 월간/시즌 resolve; spam, 정보 oracle, alt로 관계 훼손, 접속 공백 타격 위험.
- v1/v2: v2 espionage namespace만. v1 RNG draw와 `che_` 계략 의미는 변경 금지.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 정확한 성공률·비용·저항·중첩 규칙. v2는 결과 근거를 replay로 설명해야 하므로 불투명 즉시 판정을 그대로 채택할 수 없다.

### `STR-8R-INTEL-BRIDGE` — RTK8R 첩보→내통/매복→전장 발동

- `candidate_id`: `STR-8R-INTEL-BRIDGE`; 게임/버전: RTK8 REMAKE 공식 Web 매뉴얼.
- rule summary: `[사실]` 첩보는 도시 상세를 확실히 얻고 12개월 동안 그 도시에 대한 계략 성공을 돕는다. `内通` 약속은 등용 응답을 보장하고 전장에서 대상 부대를 전향시킬 수 있으며, `埋伏`은 전장에서 대상 부대를 공황 상태로 만든다. 대상·지휘관 제한이 있다.
- exact evidence: [공식 매뉴얼 `評定コマンド` → `計略`·`諜報`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/5100.html) (`E-A/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 전략 준비가 전투 phase에 소비되는 연결은 v2 Operation/replay와 잘 맞는다. single-player dependency는 `반드시 등용` 같은 절대 성공과 AI의 정보 비대칭 대체이다.
- required adaptation: `[추천]` `IntelReport(expiresAt, confidence, disclosedFields)`와 일회성 `SabotageCommitment`를 만들고, 전장 발동 때 target eligibility·counter-intel·expiry를 재검증한다. 절대 전향 대신 계약된 조건과 대상의 loyalty/agency에 따른 deterministic 분기를 둔다.
- authority/determinism/cadence/abuse: 비밀 상태는 서버만 보유; intel version+commitment+BattleState로 재현; 사령턴 준비, battle phase 소비; 정보 스크린샷 공유, alt 정찰, 반복 공황 lock, 총대장 우회 targeting 위험.
- v1/v2: v2 `operation.*`/`battle.*` bridge만 추가.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` counterplay, 중복 내통, 플레이어 소유 장수의 동의 모델.

## 명품·보물

### `ITEM-14-SEARCH-GRANT` — RTK14 탐색·수여

- `candidate_id`: `ITEM-14-SEARCH-GRANT`; 게임/버전: RTK14 공식 시스템 페이지.
- rule summary: `[사실]` 탐색은 재야 장수·명품 또는 금 등을 찾을 수 있고, 발견한 명품을 부하에게 수여하면 효과가 난다.
- exact evidence: [공식 `内政` 페이지, `探 索`](https://www.gamecity.ne.jp/sangokushi14/system-domestic.html) (`E-B/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 발견 RNG와 영구 소유 효과는 희소 자산 경쟁을 만들지만, 무작위 전리품을 v2 핵심에 넣지 않는 제품 정본과 긴장한다. single-player dependency는 전체 아이템 풀이 한 로컬 세이브에 있고 중복·거래 분쟁이 없는 점이다.
- required adaptation: `[추천]` stable item ID, 유일 소유자/위치, provenance, 발견 claim, 이전 ledger, 시즌별 공급 상한을 먼저 정의한다. asset/IP가 cleared되지 않으면 이름·이미지·표를 번들하지 않는다.
- authority/determinism/cadence/abuse: 서버 singleton ownership과 원자 이전; catalog/version/seed로 발견 replay; 개인턴 탐색·즉시 수여 intent·턴 resolve; seed fishing, 독점, alt 보관, dupe/rollback 위험.
- v1/v2: v2 item ledger 전용; v1 아이템 효과 불변.
- recommendation: **HOLD**.
- unknowns/conflict: `[UNKNOWN]` uniqueness, 전체 효과, 발견 풀/확률, 압수·상속·분실. OPENSAM-96 및 asset rights 완료를 가정하지 않는다.

### `ITEM-8R13-SOCIAL-OBJECT` — RTK8R 시장/상벌 + RTK13 취향 선물

- `candidate_id`: `ITEM-8R13-SOCIAL-OBJECT`; 게임/버전: RTK8 REMAKE + RTK13(타 넘버링) 공식 페이지.
- rule summary: `[사실]` RTK8R에서는 시장에서 명품을 사고팔며, 상벌로 주면 충성이 오르고 빼앗으면 내려간다. RTK13에서는 무구·서물·보물·술 취향이 있고 맞는 명품 선물이 친근감 증가와 호감/유대 형성에 영향을 준다.
- exact evidence: [RTK8R 매뉴얼 `都市コマンド` → `市場`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/5200.html), [RTK8R `評定コマンド` → `人事`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/5100.html), [RTK13 공식 `人間関係` → `嗜好`·`絆`](https://www.gamecity.ne.jp/sangokushi13/system1.html) (`E-A/E-B`, 모두 `R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 단순 능력치 장신구보다 선물의 사회적 의미가 v2 관계/협상에 적합하다. single-player dependency는 즉시 NPC 호감·충성 갱신과 무제한 save/reload이다.
- required adaptation: `[추천]` 물품을 `PERSONAL_EQUIPMENT|KNOWLEDGE_OBJECT|OFFICE_INSTRUMENT|STATE_REGALIA` 등 capability/claim으로 분리하고, 선물은 공개/비공개 intent, 수락·거절, 출처·소유권 이력, diminishing return을 남긴다.
- authority/determinism/cadence/abuse: 서버가 inventory·trade·gift acceptance를 원자 처리; 같은 관계 snapshot과 item provenance로 동일 diff; 거래 즉시, 관계 효과는 개인턴/월간 settle; alt 선물 세탁, 시세 조작, 충성 pay-to-win, 회수 괴롭힘 위험.
- v1/v2: v2 관계·소유권 ledger additive. v1 경매·명품 수치 미변경.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` RTK8R 가격/재고 회전, RTK13 취향별 정확한 보정치. 사람 추종자와 관인/옥새를 일반 inventory buff로 취급하지 않는 v2 백로그가 우선한다.

## 관직·작위

### `OFFICE-14-SCALE-UNLOCK` — RTK14 세력 규모 작위·수치 관직

- `candidate_id`: `OFFICE-14-SCALE-UNLOCK`; 게임/버전: RTK14 공식 시스템 페이지.
- rule summary: `[사실]` 세력 확대가 군주의 작위를 올리고 포위점령·전법 지시·출정 같은 기능을 연다. 군주는 작위에 맞는 관직을 부하에게 주며, 관직은 지휘 병수·일부 능력·봉록을 올리고 강등은 충성을 낮춘다.
- exact evidence: [공식 `内政` 페이지, `爵位・官職`](https://www.gamecity.ne.jp/sangokushi14/system-domestic.html), [공식 `戦闘` 페이지, `出 征`](https://www.gamecity.ne.jp/sangokushi14/system-battle.html) (`E-B/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 명확한 unlock은 이해하기 쉽지만 도시 수/세력 규모가 곧 법적 권한이 되는 구조는 v2 정본의 조서·인장·추천·실권 분리와 충돌한다. single-player dependency는 한 군주가 모든 임면과 봉록을 즉시 확정하는 점이다.
- required adaptation: `[추천]` 세력 규모는 claim 근거 중 하나로만 쓰고, OfficeClaim/Tenure/OperationalAssignment/NobleTitle을 분리한다. 부임·관할·속관·예산·credential이 없는 직함에는 capability를 주지 않는다.
- authority/determinism/cadence/abuse: 서버 capability resolver; 문서/claim/seat/assignment snapshot으로 결정; 사령턴 추천·수락·부임, 월간 봉록; 친구 특혜, 강등 grief, 직함 stacking, snowball 위험.
- v1/v2: v1 `officer_level`과 국가 작위는 LEGACY adapter로 보존.
- recommendation: **REJECT**(세력 규모→작위→고정 수치 unlock의 직접 이식).
- unknowns/conflict: `[UNKNOWN]` 전 작위 조건과 관직 수치. 이름/계층은 별도 evidence를 거쳐 콘텐츠 후보로만 재검토 가능하다.

### `OFFICE-8R-SCOPE-RANK` — RTK8R 신분·품계·권한 범위

- `candidate_id`: `OFFICE-8R-SCOPE-RANK`; 게임/버전: RTK8 REMAKE 공식 Web 매뉴얼.
- rule summary: `[사실]` 군주·도독·태수·군사·일반 등 신분에 따라 실행 명령과 관할이 달라진다. 도독/태수/군사/일반은 일품관~구품관 품계를 가지며, 공적이 쌓이면 봉록·편성 병수·제안 특권·임명 가능성이 오른다. 이동하면 관리 신분이 일반으로 바뀔 수 있다.
- exact evidence: [공식 매뉴얼 `ゲームの概要` → `身分と階級`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/3100.html), [공식 `評定コマンド` → `人事`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/5100.html) (`E-A/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 역할별 authority scope와 공적 이력은 멀티 분업에 적합하다. single-player dependency는 상관 AI가 승진/임명을 결정하고 플레이어가 거절·협상해도 동시 권한 충돌이 없는 점이다.
- required adaptation: `[추천]` 품계(progress), 신분(role), 법적 claim, 실무 assignment, 실제 관할을 별도 상태로 보존한다. 모든 명령은 서버 resolver가 `orderedBy`, place/formation jurisdiction, active tenure를 확인한다.
- authority/determinism/cadence/abuse: server capability; ordered merit events와 임면 decision replay; 개인턴 공적, 사령턴 추천/임명, 월간 봉록; 공적 farming, 임명 담합, 권한 대여, 직책 공석 grief 위험.
- v1/v2: v2 office 모델 additive, v1 발령은 OperationalAssignment adapter만 생성.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 품계별 정확한 threshold/특권, 사양·미부임·경쟁 claim 처리. 이는 v2 백로그의 6모델/resolver가 보완해야 한다.

## 전투

### `BATTLE-14-FORMATION-SUPPLY` — RTK14 진형·병참선·전법

- `candidate_id`: `BATTLE-14-FORMATION-SUPPLY`; 게임/버전: RTK14 공식 시스템 페이지.
- rule summary: `[사실]` 13개 진형은 야전·기동·방어·원거리·점령·산악·공성·수상 역할을 나누며, 점령 폭·이동·전법 사용 등 trade-off가 있다. 출진지와 자국 토지 연결이 끊기면 사기 저하·상태 이상으로 약화된다. 인접 우호 부대는 연계 전법을 낼 수 있다.
- exact evidence: [공식 `戦闘` 페이지, `兵站断絶`·`陣 形`·`戦 法`](https://www.gamecity.ne.jp/sangokushi14/system-battle.html) (`E-B/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 진형 역할과 가시적 보급선은 v2 실시간 formation 전투에 적합하지만 RTK14 수치/목록을 그대로 복제할 이유는 없다. single-player dependency는 실시간 지시와 AI 자동 전법, 로컬 일시정지 가능성이다.
- required adaptation: `[추천]` FormationTemplate의 mobility/weapon/protection/doctrine/supply 축으로 재구성하고, server fixed tick이 continuous coordinate·route control·morale를 계산한다. named formation/수치는 rights-cleared 콘텐츠와 balance fixture 뒤에만 활성화한다.
- authority/determinism/cadence/abuse: 서버 collision/visibility/supply/morale; tick+seed+ordered orders replay; Operation 준비 후 tactical stream; click-speed, latency, focus fire, supply-line spawn camping, disconnect 악용 위험. 짧은 order window·AI 위임·한 formation 한 명령을 적용한다.
- v1/v2: v2 BattleSession에만. v1 전투 RNG/로그 불변.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 정확한 진형 계수·전법 발동/연계 산식·patch 차이.

### `BATTLE-8R-COUNCIL-MORALE` — RTK8R 군의·증원·기상·전의

- `candidate_id`: `BATTLE-8R-COUNCIL-MORALE`; 게임/버전: RTK8 REMAKE 공식 Web 매뉴얼.
- rule summary: `[사실]` 군의에서 지휘관/참군, 배치, 전략, 수비 함정을 정한다. 인접 자국·동맹·연합 도시에 원군을 요청하고 도착일은 우호·재난·반란에 따라 달라진다. 전투는 30일 제한, 일일 부대 행동, 기상/풍향/시야, 전의, 군량, 상태 이상을 사용한다.
- exact evidence: [공식 매뉴얼 `戦闘` → `編制画面`·`軍議画面`·`戦闘画面`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/4300.html), [공식 `一年の流れ` → `戦闘`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/3200.html) (`E-A/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 사전 회의→지연 원군→전의/보급 결과는 v2 첫 장면과 직접 맞는다. single-player dependency는 전투 동안 캠페인 시간을 사실상 점유하고 총대장이 우군 전부를 조작하는 부분이다.
- required adaptation: `[추천]` 회의는 async proposal/commit deadline, 원군은 contract+arrival window, 전의/기상은 fixed-tick state로 전환한다. 다른 유저 부대는 역할 지휘관만 명령하고 전역 pause는 금지한다.
- authority/determinism/cadence/abuse: 서버 state/tick; weather schedule, reinforcement inputs, orders와 diff replay; 장수/사령턴 준비 후 fixed tick; 접속시간 우위, 명령 충돌, 원군 last-second betrayal, 시야 정보 스트리밍 위험.
- v1/v2: v2 Operation/BattleSession additive.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 실제 날짜-to-real-time 변환, 원군 거절·철회, 동시 전투의 캠페인 cadence. RTK8R 일일 턴을 그대로 채택하지 않는다.

## 내정

### `DOM-14-REGIONAL-OFFICER` — RTK14 지역 담당관·시정 조직

- `candidate_id`: `DOM-14-REGIONAL-OFFICER`; 게임/버전: RTK14 공식 시스템 페이지.
- rule summary: `[사실]` 각 지역府에 담당관을 임명해 개발·수입을 올리고, 담당관은 매턴 지역 토지를 자동 점령한다. 능력/개성이 개발 분야와 점령 폭에 영향을 준다. 시정은 전투·지원·모략·내정·인사 5부서에 장수를 배치해 정책 효과를 붙인다.
- exact evidence: [공식 `内政` 페이지, `地域担当官`·`施 政`](https://www.gamecity.ne.jp/sangokushi14/system-domestic.html) (`E-B/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 위임형 반복 관리와 인재 배치는 웹 멀티의 짧은 접속에 맞지만, 자동 영토 확장과 전역 percentage buff는 v2의 실제 시설/재고/route 계약보다 거칠다. single-player dependency는 플레이어 한 명의 조직도 최적화와 자동 매턴 처리이다.
- required adaptation: `[추천]` 군·국 기본정책→현 override→태수 위임 우선순위로 바꾸고, 담당관은 capability와 실제 Facility/ResourceNode/RouteCorridor 상태 안에서만 진행시킨다. 정책은 전국 boolean이 아니라 채택 지역·예산·인력·시간을 가진다.
- authority/determinism/cadence/abuse: 서버 assignment/project tick; 동일 정책·담당자·자원 snapshot으로 동일 progress; 사령턴 정책, 개인턴 감독, 월간 settle; 자리 독점, 무응답 위임, buff stacking, 자동 국경 침식 위험.
- v1/v2: v2 administrative layer additive.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 개발 산식, 담당관 교체 비용, 자동 점령 conflict 순서.

### `DOM-8R-MONTH-SEASON` — RTK8R 월간 개인행동·분기 평정·치안 경제

- `candidate_id`: `DOM-8R-MONTH-SEASON`; 게임/버전: RTK8 REMAKE 공식 Web 매뉴얼.
- rule summary: `[사실]` 개인 행동은 매월, 세력 평정은 1·4·7·10월에 열린다. 평정에서 내정 임무를 배정하고, 농업/상업/기술/보수/치안은 관련 능력·특기의 영향을 받는다. 금은 계절, 군량은 매년 7월에 들어오며, 치안 50/25 미만은 불안/반란과 모집·수입·원군 지연 제약을 만든다.
- exact evidence: [공식 `一年の流れ` → `季節ごとの流れ`·`住民感情`·`金と兵糧`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/3200.html), [공식 `都市コマンド` → `農村`·`市場`·`工房`·`城門`·`屯所`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/5200.html) (`E-A/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 개인 cadence와 국가 cadence 분리는 오픈삼국 예약턴 구조와 잘 맞는다. single-player dependency는 한 플레이어가 휴양으로 세계 월을 넘기고 계절 평정을 독점하는 점이다.
- required adaptation: `[추천]` 세계 시간은 서버가 전진시키고 유저는 deadline 전 intent/queue만 제출한다. threshold는 공개·versioned rule로, 프로젝트 progress는 시설·담당자·실재 자원 ledger를 소비한다.
- authority/determinism/cadence/abuse: 서버 clock/settlement; ordered commands와 month snapshot으로 replay; 개인턴/사령턴/month boundary/season; 막판 queue 교체, alt 노동, threshold grief, 장기 offline 담당자 위험.
- v1/v2: 기존 상중하순 UX는 유지하고 v2 profile에서만 월간/시즌 정책을 추가.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 수치 산식과 멀티 deadline 정책. cadence 분리 개념은 우선 foundation 후보이다.

## 인사

### `HR-8R-RECRUIT-RELATION` — RTK8R 등용·7단계 친밀·특별 관계

- `candidate_id`: `HR-8R-RECRUIT-RELATION`; 게임/버전: RTK8 REMAKE 공식 Web 매뉴얼.
- rule summary: `[사실]` 등용은 실행자의 매력, 대상의 충성/의리, 상호 친밀의 영향을 받는다. 친밀은 7단계이며 교류·연회·공동 내정으로 오른다. 경애 관계에서 의형제(본인 제외 최대 3명)나 배우자가 될 수 있고, 신뢰 이상/상생 관계는 내정·전투 연계를 만든다.
- exact evidence: [공식 `評定コマンド` → `人事`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/5100.html), [공식 `相関図と親密` → `親密`·`連携と加勢`·`義兄弟`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/3500.html) (`E-A/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 관계가 등용·협동에 연결되는 것은 적합하지만, 플레이어 간 관계를 NPC 친밀 게이지처럼 일방 변경하면 안 된다. single-player dependency는 NPC가 관계·결혼·의형제를 즉시 수락하고 모든 보너스가 주인공 중심인 점이다.
- required adaptation: `[추천]` 관계를 directed trust/respect/debt/kinship/contract와 evidence event로 분리한다. 플레이어 간 특별 관계는 양측 동의·해소 규칙, NPC는 같은 deterministic resolver를 사용한다. 등용은 제안→응답→notice/이동 window 상태기계로 만든다.
- authority/determinism/cadence/abuse: 서버 관계 graph와 consent; ordered interaction evidence로 score/diff 재현; 개인턴 상호작용·사령턴 임명·월간 decay; alt 친밀 farming, harassment, nepotism, offline 강제 이동 위험.
- v1/v2: v2 graph additive. v1 등용 RNG·로그 불변.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 정확한 친밀 증감·AI 수락·관계 해소. 성별/배우자 제한을 그대로 이식하지 않는다.

### `HR-13-BOND-NETWORK` — RTK13 가치관·소개·유대·작적

- `candidate_id`: `HR-13-BOND-NETWORK`; 게임/버전: RTK13 base 공식 시스템 페이지(타 넘버링).
- rule summary: `[사실]` 장수별 가치관과 행동 실적이 관계에 영향을 주며, 공통 지인의 소개가 미면식 장수 방문/등용을 연다. 유대는 내정·전투 협력과 기술 학습을 만들고, 적 장수와의 관계는 충성 저하와 전장 전향 공작에도 쓰인다.
- exact evidence: [RTK13 공식 `人間関係` → `価値観`·`絆`·`登用`·`籠絡／作敵`](https://www.gamecity.ne.jp/sangokushi13/system1.html) (`E-B/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` 행동 evidence와 소개 edge는 설명 가능한 관계망에 유용하다. single-player dependency는 모든 관계가 플레이어 장수를 중심으로 계산되고 전향 발동을 한 플레이어가 통제하는 점이다.
- required adaptation: `[추천]` global social graph에 interaction provenance, visibility, decay, conflict를 저장하고, 소개는 정보 접근권만 열어 성공을 보장하지 않는다. 전향은 `STR-8R-INTEL-BRIDGE`와 같은 commitment/consent/expiry 계약으로 합친다.
- authority/determinism/cadence/abuse: 서버 graph/visibility; event ledger→derived relation snapshot; 개인턴 관계 행동, 작전 phase commitment 소비; 소개망 sybil, 사적 정보 누출, 관계 점수 담합, 타 유저 부대 강제 전향 위험.
- v1/v2: v2 relationship/espionage graph만.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` 가치관 수치·관계 상한·전향 저항. RTK13 고유 명칭·표·asset은 재사용하지 않는다.

## 이벤트

### `EVENT-8R-OPT-IN-BRANCH` — RTK8R 선택형 연의전·규칙형 사건

- `candidate_id`: `EVENT-8R-OPT-IN-BRANCH`; 게임/버전: RTK8 REMAKE 공식 Web 매뉴얼.
- rule summary: `[사실]` 연의전은 플레이어가 임의 실행하며 짧은 기한, 후속 chain, 보상, 상호배타 분기, 역사와 다른 선택, 관계/사망/세력 상태 변화, 결투 결과에 따른 chain 중단을 가질 수 있다. 별도의 random event는 관계·숙명·명성·행동에 따라 발생한다.
- exact evidence: [공식 매뉴얼 `演義伝とイベント` → `演義伝の特徴`·`イベント`](https://www.gamecity.ne.jp/manual/sangokushi8-re/jp/3400.html) (`E-A/R3`, 2026-07-17 접근).
- multiplayer fit: `[추론]` opt-in·deadline·branch는 이벤트 상태기계에 적합하다. single-player dependency는 한 플레이어 선택이 공유 월드의 타 유저 장수/세력을 즉시 바꾸는 점이다.
- required adaptation: `[추천]` `EventOffer`에 audience, eligibility snapshot, expiry, visibility, quorum/consent, reserved actors/resources, branch lock, ordered effects를 둔다. 공유 월드 사건은 involved players의 응답 deadline 후 deterministic fallback을 쓴다.
- authority/determinism/cadence/abuse: 서버 eligibility/예약/branch; snapshot+선택+seed+effect order replay; notification 즉시, 개인턴 응답, 월간/시즌 effect; 이벤트 선점, actor hostage, spoiler/info leak, alt quorum, deadline grief 위험.
- v1/v2: v2 event DSL/chronicle additive. v1 월간 이벤트 불변.
- recommendation: **ADAPT**.
- unknowns/conflict: `[UNKNOWN]` multiplayer quorum과 offline fallback은 제품 결정 필요. 역사 사건은 CHRONICLE 사실이 아니라 CLASSIC/GAME_REFERENCE overlay로만 취급한다.

### `EVENT-14-CONDITION-CATALOG` — RTK14 조건형 역사·범용 이벤트

- `candidate_id`: `EVENT-14-CONDITION-CATALOG`; 게임/버전: RTK14 with PK/DLC 커뮤니티 catalog.
- rule summary: `[사실]` 공식 페이지는 scenario 설정으로 이벤트 발생을 on/off할 수 있다고만 밝힌다. 커뮤니티 catalog는 역사/DLC/PK/범용/달성/ending 범주와 일부 시작 턴·도시 지배·장수 소속·선행 사건 조건 및 세력/도시/장수 side effect를 기록한다.
- exact evidence: [RTK14 공식 `その他` → `各種設定`](https://www.gamecity.ne.jp/sangokushi14/system-other.html) (`E-B/R3`), [WIKIWIKI `イベント` → `歴史イベント`·`汎用イベント`, site-displayed last-modified 2026-07-04 09:18:30](https://wikiwiki.jp/sangokushi14/%E3%82%A4%E3%83%99%E3%83%B3%E3%83%88) (`E-C/R4`, 2026-07-17 접근).
- multiplayer fit: `[추론]` declarative prerequisites/effects 발견에는 유용하지만 팬 위키의 조건·수치가 완전하거나 공식이라고 볼 수 없다. single-player dependency는 싱글 세이브용 전역 mutation이다.
- required adaptation: `[추천]` 복사/ingest하지 말고 hand-authored schema test의 질문 목록으로만 쓴다. 각 실제 event는 official/game capture 또는 별도 승인된 source, rights review, actor conflict/consent fixture를 통과해야 한다.
- authority/determinism/cadence/abuse: 서버 event matcher; manifest/version/snapshot/branch/ordered diff hash; month/season boundary; 조건 선점, 일부러 actor 이동·처단해 chain 차단, 반복 reward, hidden-condition oracle 위험.
- v1/v2: v2 CLASSIC overlay only; CHRONICLE history claim으로 승격 금지.
- recommendation: **HOLD**.
- unknowns/conflict: `[UNKNOWN]` 전체 조건·효과·patch별 번호, 공식 정확성, 재배포 권리. 팬 전략 메모와 댓글은 evidence에서 제외했다.

### `[추론]` 우선순위와 foundation order

1. `F0 evidence/rights/version`: source/version/rights/UNKNOWN을 먼저 고정한다. OPENSAM-96~100의 산출물은 준비됐다고 간주하지 않는다.
2. `F1 authority/time/replay`: server clock, personal/chief/tactical cadence, idempotency, canonical serialization과 deterministic replay를 모든 축의 공통 기반으로 둔다.
3. `F2 identity/relationship/ownership`: stable actor/place/item ID, directed relation evidence, consent, singleton item ledger, Office claim/assignment를 만든다.
4. `F3 domestic/contracts`: `DOM-8R-MONTH-SEASON`, 지역 위임, 자원 ledger, 일반 외교 contract를 먼저 세워 후속 비용·권한을 제공한다.
5. `F4 diplomacy/espionage`: `DIP-*`와 `STR-*`를 prepare→response/counter→resolve 상태기계로 붙인다.
6. `F5 operation/battle`: `BATTLE-*`의 formation·보급·원군·회의를 Operation/BattleSession에 연결한다.
7. `F6 event orchestration`: 앞선 권한·관계·자원·전투 상태를 소비하는 opt-in event/CLASSIC overlay를 마지막에 연다.

우선 수직 슬라이스 추천은 `DOM-8R-MONTH-SEASON → DIP-8R-COALITION의 원군 의무 → BATTLE-8R-COUNCIL-MORALE의 회의/지연 원군 → replay → EVENT-8R-OPT-IN-BRANCH의 전후 선택`이다. 이는 `[추론]`이며 A2 승인 전 구현 티켓이 아니다.

### 공통 UNKNOWN·conflict

- 모든 공식 페이지의 정확한 build/patch fingerprint와 숨은 산식, RNG draw, AI 보정은 `UNKNOWN`이다. 공식 매뉴얼/홍보 페이지는 executable spec이 아니다.
- RTK14 official system 페이지가 base와 PK 내용을 어디까지 혼합했는지 `UNKNOWN`이다. 이 문서는 페이지에 직접 표시된 규칙만 후보로 삼았다.
- WIKIWIKI에는 open license나 opensamguk 재배포 grant가 관찰되지 않았다. bulk scrape, 표/문장/이미지 번들, asset hotlink는 금지한다.
- 역사·연의·GAME_REFERENCE를 한 claim으로 합치지 않는다. 이 catalog의 모든 RTK rule은 GAME_REFERENCE다.
- 아이템·관직·관계·이벤트가 플레이어 consent와 offline fallback을 어떻게 처리할지는 제품 결정이 남아 있다. 이 결정을 숨은 RNG나 NPC 규칙 복제로 대체하지 않는다.

### 검증 명령

```bash
test -s docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md
test "$(rg -c '^## (외교|계략|명품·보물|관직·작위|전투|내정|인사|이벤트)$' docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md)" -eq 8
rg -n 'candidate_id|exact evidence|URL|license|authority|determinism|cadence|abuse|ADOPT|ADAPT|HOLD|REJECT|UNKNOWN|conflict' docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md
git diff --check -- docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md
tools/agent-system/check.py
```

URL은 2026-07-17에 위 순서대로 직렬 접근했다. broad crawl, asset 다운로드, query/attachment 경로 접근, 403/429 우회는 하지 않았다. 직접 열어 확인한 URL만 candidate evidence에 사용했다.
