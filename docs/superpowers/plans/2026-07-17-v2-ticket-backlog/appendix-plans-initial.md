# v2 로드맵 문서 4종 분해 (읽기 전용)

읽은 파일 4개 전문 완독. 아래는 문서에 명시된 내용만으로 구성했고, 완료 기준이 없는 항목은 "문서 미명시"로 표기했다.

---

## 문서 1 — `docs/superpowers/specs/2026-07-13-v2-nation-identity-rework.md`

### 목적/범위 요약
v1의 국가타입 15개를 하나의 배타적 `nationType` enum으로 확장하지 않고, 국가 정체성을 5요소(정통성 `LegitimacySource[]` + 통치형태 `GovernanceForm[]` + 전통 `Tradition[]` + 조직망 `NetworkPresence[]` + 정책 `ActivePolicy[]`) 조합으로 재해석한다. v1의 15개 효과는 PHP 패리티로 그대로 동결(회귀 금지선)하고, `LEGACY` 월드만 기존 `NationTypeModule`을 쓴다. 각 정체성은 단순 수치 보너스가 아니라 명령·시설·병력·외교·점령·AI 루프를 바꿔야 하며, 첫 구현은 13개 유효 프리셋 중 유가·태평도·도적 3개만 수직 슬라이스로 만든다. 비범위는 v1 `ActionNationType` 효과·수치·골든 변경이다.

### 구조 (문서에 있는 그대로)
명시적 phase 넘버링 없음. 대신: §3 정본 데이터 모델(3.1 지지기반 벡터 / 3.2 통치 단계 / 3.3 조직망 / 3.4 데이터 계약) → §4 게임플레이 계약(8개 표면) → §5~6 15개 항목 리워크 → §7 명령 체계 연결 → §8 외교 리워크 → §9 정체성 변화 규칙(6단계) → §10 AI 계약 → §11 첫 구현 수직 슬라이스(유가→태평도→도적) + 수용 조건.

### 티켓 후보
1. **정체성 데이터 계약 정의하기** — `FactionIdentityProfile`(factionId, stage, legitimacyByAudience{}, governanceForms[], traditions[], activePolicies[], institutionalTensions[], contentProfile, version)와 `IdentityPreset`(id, canonicalName, explanatorySubtitle, legacyCode, initialStage, *Seeds, *Capabilities, transitionRules, provenance) 스키마를 확정한다. IdentityPreset은 초기조건만 만들고 실제 효과는 profile+network가 계산한다. 출처: §3.4 데이터 계약. 선행 의존성: 문서 미명시. 완료 기준: 문서 미명시.
2. **지지 기반 벡터(6청중) 구현하기** — 단일 정통성 수치 대신 `HAN_COURT`/`LOCAL_ELITES`/`COMMONERS`/`RELIGIOUS_ADHERENTS`/`MILITARY_FOLLOWERS`/`FRONTIER_COMMUNITIES` 청중별 정통성을 기록한다. 조정 책봉이 벡터를 바꾸되 과거 조직망은 즉시 사라지지 않는다. 출처: §3.1. 선행: 문서 미명시. 완료 기준: 문서 미명시.
3. **통치 단계 enum 구현하기** — `MOVEMENT`/`CONFEDERATION`/`TERRITORIAL_REGIME`/`BUREAUCRATIC_STATE`/`DYNASTIC_CLAIM` 5단계와 프리셋별 시작 단계(태평도=MOVEMENT, 흑산형 도적=CONFEDERATION 등)를 정의한다. 출처: §3.2. 선행: 티켓1. 완료 기준: 문서 미명시.
4. **공간 조직망(NetworkPresence) 모델 구현하기** — networkId/placeId/visibility(PUBLIC|COVERT|SUPPRESSED)/adherents/leadership/cohesion/supplies/localSupport/hostNationRelation을 도시·통행로에 남긴다. 도시 점령과 행정·군사·조직망 장악을 서로 다른 상태로 분리한다. 출처: §3.3. 선행: 티켓1. 완료 기준(§11 수용조건에서 파생): 한 도시에 공식 소유국과 다른 태평도 조직망이 동시 존재 가능해야 함.
5. **정체성 canonical 명령 10종 등록하기** — 개인턴 `personal.network.establish/operate/negotiate`, 사령턴 `chief.identity.convene/adopt/appoint/proclaim/suppress/accommodate`, 전략 `polity.transition.resolve`를 기존 턴 링에 projection한다. 실시간 전술 명령은 국가 성향 이름을 검사하지 않는다. 출처: §7. 선행: 문서 미명시(단, canonical id 정본은 `2026-07-12-v2-command-catalog-and-rollout.md`). 완료 기준: 문서 미명시.
6. **외교 조항 9종 추가하기** — 기존 동맹/불가침/선전포고 위에 `RecognitionTerm`/`AutonomyTerm`/`ReligiousTolerance`/`SafePassageTerm`/`TributeTerm`/`HostageTerm`/`AmnestyTerm`/`GarrisonTerm`/`NetworkDisclosureTerm`를 얹는다. 도적 귀순, 오두미도 자치, 태평도 사면, 종횡가 다자보증, 유가 책봉이 같은 엔진에서 다른 플레이가 되게 한다. 출처: §8. 선행: 문서 미명시. 완료 기준: 문서 미명시.
7. **정체성 변화 규칙 파이프라인 구현하기** — 드롭다운 즉시 변경 금지. convene(개혁 의제)→청중별 찬반 세력→개인턴 설득/감찰/진압/전환→정책·임명 일치 기간→`polity.transition.resolve`(채택/타협/분열/반란)→이전 조직망 야당·잠복·자치로 잔존, 6단계를 구현한다. 출처: §9, §11. 선행: 티켓5. 완료 기준(§11): 정체성 변경이 과거 조직망과 반대 세력을 보존해야 함.
8. **AI utility 정체성 가중 구현하기** — 모든 AI가 플레이어와 같은 명령을 쓰되 utility = survival + legitimacyAudienceFit + networkContinuity + resourceFeasibility + militaryRisk + treatyReliability + institutionalPromiseFulfilment − factionResistance − overextension 항을 정체성별로 다르게 가중한다. 프리셋 전용 치트 스크립트 금지. 출처: §10. 선행: 티켓2,4. 완료 기준(§11): AI와 플레이어가 동일 명령·비용·전환 규칙 사용.
9. **유가 프리셋 수직 슬라이스 구현하기** — 도시 학교·추천망, `HAN_COURT` 정통성, 관료 임명을 검증한다. 사령턴(학교 설치/효렴·현량 추천/국가 의례/관직 서열 등), 개인턴(경학 강론/인재 천거 등), 병력(장수 추천·관직 수용), 전환(법가·명가·덕가 혼합). 출처: §6.10, §11. 선행: 티켓1~8. 완료 기준(§11): 유가 학교 파괴 시 생산량이 아니라 관료 후보·명사 관계·정통성이 손실돼야 함.
10. **태평도 프리셋 수직 슬라이스 구현하기** — 타국 도시의 은밀한 `方` network, 포교, 동시 거병, 운동의 국가화를 검증한다. 통치 단계 MOVEMENT 시작, 사령턴(대현량사 포고/방 거수 임명/동시 거병일 등), 연의 계층은 `ROMANCE_ATTESTED`로만. 출처: §6.13, §11. 선행: 티켓1~8. 완료 기준(§11): 한 도시에 공식 소유국과 태평도 조직망 동시 존재.
11. **도적 프리셋 수직 슬라이스 구현하기** — 비도시 산채·route control·소두목 연맹·귀순 책봉을 검증한다. 통치 단계 CONFEDERATION 시작, 도시 없이 통행로·영향력 행사, 전환(산채 연맹→조정 공인→군정→관료국). 출처: §6.3, §11. 선행: 티켓1~8. 완료 기준(§11): 도적 연맹이 도시 없이 산채·통행로만으로 존속, 조정 책봉이 도적 조직을 즉시 삭제하지 않고 정통성·외교·직책만 변화.
12. **v1 NationTypeRegistry 동결 회귀 게이트 세우기** — v2 데이터/UI가 v1 코드를 읽되 v1 효과를 v2 제도로 자동 변환하지 않도록, v1 `NationTypeRegistry` 테스트와 골든이 byte 단위로 불변임을 게이트로 고정한다. 출처: §2, §11 수용조건. 선행: 없음(선행 게이트). 완료 기준(§11): v1 NationTypeRegistry 테스트·골든 byte 불변.
13. **CHRONICLE/CLASSIC 콘텐츠 프로필 고증 게이트 세우기** — `CHRONICLE`에서 묵가 기사단·승병·기상마법·완성된 태평도 관료국을 역사 사실로 생성하지 않도록 콘텐츠 행별(직접 사료/학술 복원/연의/게임 참고) 구분을 강제한다. 출처: §6(각 프리셋 역사 경계), §11, §12. 선행: 티켓1. 완료 기준(§11): CHRONICLE에서 상기 4항목 미생성.
14. **나머지 10개 유효 프리셋 순차 구현하기** — 슬라이스 3개(유가·태평도·도적) 검증 후 덕가·도가·명가·묵가·법가·병가·불가·오두미도·음양가·종횡가를 §6.1~6.12의 프리셋별 상세안(정통성/사령턴/개인턴/도시/군대/외교·점령/긴장/AI/전환)대로 추가한다. 출처: §5, §6.1~6.12, §11. 선행: 티켓9~11 검증. 완료 기준: 문서 미명시(프리셋별 상세안이 사양).
15. **중립·없음 특수 상태 처리하기** — `중립`(실용 연합정권): 고유 보너스 없는 벌칙이 아니라 유연한 초기 상태, 첫 건국 회의에서 제도1+정책1 선택. `없음`(`None`): 저장·시드 호환 내부 상태, 국가가 None인 채 사령 명령 실행 시 validation 실패. 출처: §6.14, §6.15. 선행: 티켓1. 완료 기준(§6.15): None 상태 사령 명령이 validation 실패해야 함.

### 비범위 (non-goals)
- v1 `ActionNationType` 효과·수치·골든 변경(§ 헤더 비범위, §1, §2).
- 15개를 하나의 배타적 enum으로 재통합(§1).
- 드롭다운에서 즉시 갈아끼우는 성향 변경(§9).
- CHRONICLE에서 묵가 기사단·승병·기상마법·완성된 태평도 관료국을 역사 사실로 생성(§11).
- 정체성 변경 시 과거 조직망·반대 세력 삭제(§9, §11).
- 최종 수치 보정이 고유 플레이의 주체가 되는 것(§4: 첫 효과는 +10%가 아니라 새 행동·조직·계약·전환).

---

## 문서 2 — `docs/superpowers/plans/2026-06-28-opensamguk-v2-game-design-plan.md`  ⚠️ superseded

### 목적/범위 요약
v2를 v1 패리티와 분리된 별도 제품 방향으로 설계한다: 삼모의 장수 턴 입력 감각을 유지하되 전쟁을 전장 상태 머신 replay로, 플레이어 아래 가신·추종·봉신이 제안·이해관계로 움직이는 실시간 봉건 정치 전쟁 게임. 런타임 LLM 없이 deterministic rule score+템플릿으로 제안·회의·서사를 만든다. **헤더에 `상태: superseded-by-2026-07-12-product-spec`, `후속 정본: docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md`로 명시** — 최신 정본 아님. 단, 2026-07-14 정정으로 3D를 v2 기본 지도·전장 surface로 확정한 항목은 유효.

### 구조 (§10 구현 로드맵, 순서 보존)
- **Phase V2-0 — 문서와 증거 고정**
- **Phase V2-1 — 전쟁 replay spine**
- **Phase V2-2 — 가신 1명 vertical slice**
- **Phase V2-3 — 어전회의**
- **Phase V2-4 — 봉토와 도독부**
- **Phase V2-5 — 동적 전쟁 첫 완성**
- §11 우선순위: 1)3D world coordinate·selection·terrain foundation 2)replay schema 3)가신 proposal schema 4)court council schema 5)fief/feudal contract schema 6)UI timeline·3D replay presentation.

### 티켓 후보 (superseded 플랜 — 06-29 실행플랜에 재편·상세화됨. 개념 출처로만 사용 권장)
1. **v2 문서·증거 고정하기 (V2-0)** — myosam 37페이지 위키 컴파일, samnet 공개 화면 feature snapshot, v2 ERD 초안, v1 패러티/v2 확장 브랜치 분리 원칙 문서화, 도시3·route2·terrain1·formation4 3D foundation proof + camera/picking/performance 계약. 출처: §10 Phase V2-0. 완료 기준(Exit): `docs/wiki/pages/refs/myosam-help-corpus.md` 갱신, v2 schema spike 문서 존재.
2. **전쟁 replay spine 구축하기 (V2-1)** — `battle_replays`/`battle_replay_phases` schema, 공격 명령 1개가 replay 생성, phase=approach/encounter/field/siege/aftermath, UI replay timeline read-only. 출처: §10 Phase V2-1. 완료 기준(Exit): 같은 입력·버전·seed로 wall-clock id/timestamp 제외 deterministic replay body+hash, 전쟁 결과 로그와 replay 연결.
3. **가신 1명 vertical slice 구현하기 (V2-2)** — `general_retainers`/`retainer_proposals`/`retainer_interactions`, 참모 가신 1명 자동생성/영입, 전쟁·내정·외교 중 1종 제안, 승인 시 명령 큐/정책 연결. 출처: §10 Phase V2-2. 완료 기준(Exit): 제안 근거 score 저장, 승인/거부가 신뢰/충성에 영향.
4. **어전회의 구현하기 (V2-3)** — `court_councils`/`council_opinions`/`bias_profiles`, 선전포고 의제 1종, 참모/군수관/사신 의견 생성, 플레이어 또는 NPC ruler AI 결정. 출처: §10 Phase V2-3. 완료 기준(Exit): 같은 월드 상태·seed에서 같은 의견/결정 재현, UI에 편향·근거 표시.
5. **봉토와 도독부 구현하기 (V2-4)** — `subfactions`/`fiefs`/`feudal_contracts`, 도시1 봉토 부여, 봉신 세금/징병 일부 중앙 납부, 중앙 원군 수락/지연/축소. 출처: §10 Phase V2-4. 완료 기준(Exit): 봉신 충성/자율성이 명령 지연에 영향, 봉토 회수/보상 상호작용 가능.
6. **동적 전쟁 첫 완성 시나리오 구현하기 (V2-5)** — 참모 제안→어전회의 반대→플레이어 승인→봉신 원군 지연→replay 접근지연·공성손실→전후 참모 신뢰·봉신 충성 변화의 통합 흐름. 출처: §10 Phase V2-5. 완료 기준(Exit): 테스트 fixture와 UI 수동 QA로 재현.
7. **v2 도메인 schema spike 작성하기** — `docs/superpowers/specs/2026-06-28-v2-domain-schema-spike.md`에 전쟁 replay/가신 proposal/어전회의/봉건 계약 ERD 확정 + 시스템별 첫 vertical slice 테스트 시나리오 1개씩. 그 뒤 `battle_replays`부터 migration 시작. 출처: §13 첫 번째 실행 과제. 완료 기준: 문서 미명시(초기 테이블 후보 목록 §8.2 참조).
8. **v2 시간 cadence 분리 설계하기** — simulation tick / general turn / game date(1개월 3순) 3레이어 분리. 출처: §8.1. 완료 기준: 문서 미명시.

(참고 개념: §5.2 가신 6속성 충성/신뢰/야망/기질/전문성/편견, 5유형 참모/호위/군수관/정찰/사신; §5.5 병력 풀 4종 중앙예비군/도독부예비군/도시예비군/장수사병; §7 MVP 월드 도시20·세력3·장수50 — 이들은 06-29에서 재상술됨.)

### 비범위 (§12)
- samnet/myosam 화면·자산 그대로 복제.
- v1 패러티 goldens를 v2 편의로 변경.
- 런타임 LLM으로 가신 발화.
- 처음부터 전체 삼국지 지도 제작.
- 첫 MVP에서 모든 병종/건물/관직 구현.

---

## 문서 3 — `docs/superpowers/plans/2026-06-29-v2-release-implementation-plan.md`  (execution-plan)

### 목적/범위 요약
06-28 게임 기획안(현재 superseded된 base)을 실행 계획으로 구체화한 문서. v1 운영면(로그인·로비·지도·장수·국가·도시·예약턴·상/중/하순·로그)을 유지한 채 4가지 출시 차별점(현재 조작 대상 중심 UI / 가신·추종·부곡 다중 주체 커맨드 / 대규모 침공·협공·요격·농성·공성 동적 전쟁 / 부세력·봉토·도독부·중앙 명령 지연 봉건 정치)을 얹는다. samnet은 운영 감각·전투 모티브, myosam은 예약턴·관직·부대·요격/농성/공성 룰 근거로 쓰되 화면·자산·수치·명칭은 복제하지 않는다. 06-28을 근거로 삼으므로 06-29의 phase 넘버링(V2-P0~P8)은 07-13 활성 플랜의 게이트 체계와 별개다.

### 구조 (§4 출시 구현 순서, 순서 보존)
- **V2-P0: v1 운영 안정화** (작업 10개 + 검증 7개)
- **V2-P1: 조작 대상 패널과 대상별 커맨드 기반**
- **V2-P2: 부곡 foundation**
- **V2-P3: 작전과 협공**
- **V2-P4: 동적 전쟁 replay**
- **V2-P5: 가신 1명 vertical slice**
- **V2-P6: 어전회의와 편견**
- **V2-P7: 도독부, 봉토, 부세력**
- **V2-P8: 출시 수직 슬라이스**

### 티켓 후보 (각 phase의 작업은 세분 가능 — 아래는 phase 단위 + 핵심 하위작업)
1. **v1 운영 안정화하기 (V2-P0)** — 서버/컨테이너 시간대 `Asia/Seoul` 고정, `ServerClock` UTC anchor/KST 표기 분리, 로그인·로비·메인·정보·로그 전부 월·순 표시, 상/중/하순 월 이벤트 3회 실행 문제를 월 경계 이벤트+순 이벤트로 분리, 메인 UI 현재 장수 도시 기본화, 징병 UI 가능 병과만 노출+전체 토글, 명령예약·서신·견문 e2e 잠금, 3D foundation proof(도시3·route2·terrain1·formation4), 임관 전후 e2e, 명령 활성 4조건(actor capability/선택 장수/선택 queue slot/server candidate set) 분리+거부 사유 API·UI 동일 노출. 출처: §4 V2-P0 작업1~10. 완료 기준(검증): production health·/game/s1, 로그인→명령예약→표반영→데몬소비→결과로그, web/game typecheck/build, backend command lifecycle focused tests, canvas nonblank·picking·camera·context-loss·FPS, 재접속 후 소속·도시·봉록·채널·로그 단일 사건 id, capability fixture(일반장수 회의실/등용장/부대창설 허용, 관직임명 거부, 부대장 전용 집합 거부).
2. **조작 대상 패널·대상별 커맨드 기반 만들기 (V2-P1)** — 메인 상단 `조작 대상` 영역, 기본 대상=본인 장수, 도시/국가/장수 카드를 단일 `현재 대상 상태 패널`로 통합+3D picking 연결, 커맨드 모달이 선택 대상 id/type 포함, 서버 권한·외교·인접성·보급 평가 목적지 후보 API(3D scene·텍스트 목록 동일 candidate id), 미지원 대상 비노출. 출처: §4 V2-P1. 완료 기준(검증): 본인 장수 선택 시 기존 커맨드 예약 동작, 도시 선택은 inspect/focus만·명령 선택 후에만 목적지 초안 승격, capability fixture 권한별 차등, queue slot 미선택 vs actor 권한 부족 서로 다른 reason code, 36턴 예약표 모바일/데스크톱 미파손.
3. **부곡 foundation 구축하기 (V2-P2)** — `general_bugok` schema spike+migration, 기존 장수 병력을 기본 부곡 1개로 materialize, 부곡 위치/병종/병력/훈련도/사기/보급 read API 노출, UI 부곡 탭, 명령은 아직 기존 장수 병력 값 사용하되 read 모델은 부곡 병행. 출처: §4 V2-P2. 완료 기준(검증): 기존 전투/징병 결과 미파손, 장수 병력과 기본 부곡 병력 일치, 장수·부곡 위치 분리 가능 fixture 존재. (필수 테이블 후보 §3.3: general_bugok/bugok_orders/bugok_movements/bugok_supply_state/operation_bugok_assignments.)
4. **작전·협공 구현하기 (V2-P3)** — `operations`/`operation_participants`/`operation_routes`, 작전 생성 UI(목표 도시·참여 대상·경로·도착 순), 기존 `출병`을 단독 작전 1개로 wrapping, 다수 장수/부곡 협공 판정, 도착 window·경로 충돌·인접 도시 동시 압박 계산, 협공 성공/실패 replay phase 기록, `operation_routes`를 3D route corridor로 표시+집결/교전 anchor 전술 seed 전달. 출처: §4 V2-P3. 완료 기준(검증): 같은 seed·입력 같은 replay, 단독출병/2부대협공/원군지연 3 fixture, 협공 로그가 월/순과 함께 기록.
5. **동적 전쟁 replay 구현하기 (V2-P4)** — `battle_replays`/`battle_replay_phases`, phase=approach/scout/intercept/field/siege/urban/aftermath, 요격/농성/공성/지형/함정/부곡 보급을 phase input 기록, UI timeline read-only, 정세 로그와 replay 연결, replay가 동일 terrain/spatial snapshot 재구성+정사영 지휘 카메라 keyframe 선택 재생. 출처: §4 V2-P4. 완료 기준(검증): 전투 후 replay 조회 가능, phase별 근거 JSON 저장, 월/순 로그와 replay timestamp 일치.
6. **가신 1명 vertical slice 구현하기 (V2-P5)** — `general_retainers`/`retainer_proposals`/`retainer_interactions`, 본인 장수에 참모 가신 1명 생성, 전쟁·내정·외교 중 1개 제안을 rule score 생성, 조작 대상 패널 가신 슬롯 표시, 승인 시 작전 초안/커맨드 예약 연결, 거부 시 신뢰/충성/냉각시간 변화. 출처: §4 V2-P5. 완료 기준(검증): 런타임 LLM 호출 없음, 같은 world state·seed 같은 제안, 승인/거부 저장+UI 표시.
7. **어전회의·편견 구현하기 (V2-P6)** — `court_councils`/`council_opinions`/`bias_profiles`, 선전포고/대규모 침공 의제 1종, 참모/군수관/사신/도독부 대표 의견 생성, 찬성·반대·보류+확신+근거+편향 저장, 플레이어 결정을 작전/명령 큐 변환. 출처: §4 V2-P6. 완료 기준(검증): 같은 seed 같은 의견·결론, 편향 요인 UI 표시, 결정 후 실제 작전 초안 생성.
8. **도독부·봉토·부세력 구현하기 (V2-P7)** — `subfactions`/`fiefs`/`feudal_contracts`, 도시1 봉토 부여, 봉토가 세금/징병/부곡 일부 보유, 중앙 원군 요청 수락/지연/축소 룰, 봉신 충성/자율성이 작전 결과 반영. 출처: §4 V2-P7. 완료 기준(검증): 봉토 수입 일부 중앙 납부, 원군 요청 결과 replay 기록, 봉토 회수/보상 상호작용 가능.
9. **출시 수직 슬라이스 통합하기 (V2-P8)** — 접속→조작 대상 패널(본인/부곡/가신)→참모 요격 공백 제안→어전회의 군수관 보급 반대→대규모 침공 승인→부곡2+추종1 편성→봉토 원군 지연→접근·정찰·요격실패·공성·전후 replay→가신 신뢰·봉신 충성·도시 민심 변화→3D route·교전 anchor가 전술 전장·replay 동일. 출처: §4 V2-P8. 완료 기준(검증): production 수동 QA, 두 브라우저 세션 정세/로그 동기화, replay·커맨드 결과 조회, 서버/컨테이너 KST, 월/순 로그 누락 없음, 3D 지도 desktop/mobile 시각 gate.
10. **v2 도메인 schema spike 작성하기** — `docs/superpowers/specs/2026-06-29-v2-domain-schema-spike.md`에 조작 대상/부곡/작전/replay/가신/어전회의/봉토 ERD 고정. 첫 코드 변경은 메인 UI 조작 대상 패널+KST 시간 고정, 그 다음 부곡 read model+migration. 출처: §5 바로 다음 구현 단위. 완료 기준: 문서 미명시.
11. **역사 고증 데이터 계약 만들기** — 고증을 강제 재현이 아니라 선택 가능한 제약·확률 보정으로. `historical_profiles`/`general_relationships`/`scenario_placement_rules`/`legitimacy_events`, 출신·친소·원한·관직 적성·병종 선호 기록, 비역사 선택은 관계/명분/충성/민심 비용. 출처: §3.5. 완료 기준: 문서 미명시.

### 비범위 / 출시 보류 조건
- 복제 금지: samnet/myosam 화면·자산·수치·명칭(§1, §2.1); 33병종 세부 수치 미도입(§2.1).
- **출시 보류(§6)**: 명령 예약이 표에 미반영 또는 데몬 소비 결과 관측 불가 / 서신·견문 결과 미출력 / 월·순 로그 누락 / 월 이벤트 상·중·하순 3회 실행 / production 로그인→진입→예약→결과 관측 실패 / 가신 제안·회의 의견이 LLM 호출 의존 / 작전·replay가 seed 재현 불가.

---

## 문서 4 — `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md`  (active-plan)

### 목적/범위 요약
이번 달 v2 서버 오픈 전에 v1을 운영 가능한 기준선으로 고정하고 v2 구현이 v1 안정성을 깨지 않게 하는 활성 플랜. 평가를 기능 목록 점검이 아니라 "게임이 시작→여러 유저·국가→커맨드 누적→끝 상태까지 가는가"로 고정한다. B0(공백지 다국가 커맨드)·B0.5(전체 공개 커맨드 계약)·B1(공백지 player-bot 천통, B1a/B1b)·B2(운영형 장기 스모크) 게이트 체계를 쓰며, 2026-07-13 실행 결과 B0/B0.5/B1(12개월·천통 포함)·표적 lifecycle·logic/engine 전체 gate·agent-system이 모두 녹색으로 기록됨. 남은 열린 작업은 v1 안정화 체크리스트 문서화, B2 운영 스모크, v2 격리·3D·sandbox·첫 슬라이스 게이트다.

### 구조 (순서 보존)
- **현재 버전 평가** — 7축(시나리오 부팅/명령 intake/외교 자동화/장기 시뮬/프론트 운영/배포·운영/패러티 회귀) + v2 전 차단 기준.
- **2026-07-13 실행 결과** — 게이트별 녹색 증거(B0/B0.5/B1/표적 lifecycle/logic gate/engine gate/agent-system).
- **평가 계획** — B0 / B0.5 / B1(B1a 자동외교, B1b 후속) / B2.
- **구현 원칙** · **자동외교 정책**(5상황 표).
- **이번 달 실행 순서** (10단계, 다수 완료 표기):
  1. B0 공백지 다국가 커맨드 IT (완료)
  2. B0.5 전체 커맨드 선택/계약 게이트 (완료)
  3. v1 안정화 체크리스트
  4. B1 player-bot 설계 (B1a 완료)
  5. B1 12개월 윈도 (완료)
  6. B1 천통 확장 (완료)
  7. v2 V2-0A production 격리 게이트
  8. v2 V2-G0 역사 지리·3D 공간 계약
  9. v2 V2-0B sandbox runtime 적재
  10. v2 V2-1 첫 수직 slice
- **오픈 전 Go/No-Go**.

### 티켓 후보 (완료 표기 항목 제외, 열린 작업 중심)
1. **v1 안정화 체크리스트 문서화하기 (실행순서 3)** — seed/load/intake/flush/read/SSE/deploy 항목별 명령과 판정 기준을 문서화한다. 출처: 이번 달 실행 순서 3. 선행: 없음. 완료 기준: 항목별 명령·판정 기준 문서화.
2. **B1b 자동외교 후속 상태전이 닫기** — `che_종전제의`/`che_불가침제의`/`che_불가침파기제의`와 수락 3종(`che_종전수락`/`che_불가침수락`/`che_불가침파기수락`)의 정책 상태전이를 닫는다(B1a 선전포고는 완료). 출처: 평가 계획 B1b, 자동외교 정책 표. 선행: B1a(완료). 완료 기준(자동외교 표): war→trade 전환, non-aggression 상태·term 기록, trade 상태 복귀 후 선전포고 가능.
3. **B2 운영형 장기 스모크 구축하기** — s1/QA profile 60초 cadence로 같은 루프를 축소 실행하며 Redis intake·game-engine daemon·game-api read·SSE 관측 포함, 운영 중 no-op/false-deny/stale frontend를 잡는다. `ScenarioImporter.importAll` 전체 원자성이 오픈 리스크이므로 seed 중간 실패 시 `world_state`만 남아 재시도 skip 안 되는지 검증한다. 출처: 평가 계획 B2. 선행: B0/B1(완료). 완료 기준: fresh DB seed 중간 실패 후 재시도 미skip 검증.
4. **V2-0A production 격리 게이트 세우기 (실행순서 7)** — production profile에 v2 route·bean·migration·catalog loader가 0이고 v1 schema·seed·golden diff가 0임을 게이트로 고정한다. 출처: 이번 달 실행 순서 7, Go/No-Go(v2 migration/schema가 s1 production world 오염 시 No-Go). 선행: 문서 미명시. 완료 기준: v2 route/bean/migration/catalog loader 0 + v1 schema/seed/golden diff 0.
5. **V2-G0 역사 지리·3D 공간 계약 게이트 세우기 (실행순서 8)** — 140년 baseline→189년 delta와 activation manifest를 검증하고 synthetic·실제 source catalog 각 2,000개가 3D gate를 통과하게 한다. 출처: 이번 달 실행 순서 8. 선행: 문서 미명시. 완료 기준: baseline→delta·activation manifest 검증 + synthetic/실제 각 2,000개 3D gate 통과.
6. **V2-0B sandbox runtime 적재하기 (실행순서 9)** — `ACTIVE` catalog만 v2 sandbox에 적재되고 production과 독립임을 보장한다. 출처: 이번 달 실행 순서 9. 선행: V2-0A, V2-G0. 완료 기준: ACTIVE catalog만 sandbox 적재 + production 독립.
7. **V2-1 첫 수직 slice 관측 게이트 세우기 (실행순서 10)** — command result lifecycle과 조작 대상 갱신이 실제 화면에서 관측되게 한다. 출처: 이번 달 실행 순서 10, Go/No-Go(production-like sandbox에서 장수 생성 후 화면 갱신이 턴 완료 무관 관측). 선행: V2-0B. 완료 기준: command result lifecycle·조작 대상 갱신 실제 화면 관측.
8. **배포 전 체크리스트 고정하기** — image tag, seed code, runner health, disk headroom, DB migration 상태를 배포 전 체크리스트로 확인한다. 출처: 현재 버전 평가(배포·운영 축), Go/No-Go. 선행: 없음. 완료 기준(Go): 체크리스트가 상기 5항목 확인.

### 비범위 / 원칙·차단 조건
- **구현 원칙**: 억지 종착상태(직접 `isunited`/도시 소유권 조작) 금지 / 플레이어 국가 최소 5·기본 6개 / 플레이어 장수 기본 60명(국가당 10) / 내정 캐릭터를 농업·상업만이 아니라 치안·성벽·수비·기술로 분화 / 자동외교는 편의 아니라 전략 입력(선전포고·종전·불가침·파기 모두 상태전이 검증) / 모든 역할군이 전문 커맨드만이 아닌 전체 공개 커맨드 선택 가능(역할별 누락은 v1 안정화 실패) / 모든 행동은 실제 명령 핸들러 통과(테스트 helper가 world row 직접 완성 금지) / 빠른 게이트(B0)·천통 게이트(B1)·운영 스모크(B2) 분리 / **v2 구현은 B0 또는 B0.5를 깨면 중단, B1은 오픈 전 hardening gate로 승격**.
- **No-Go(오픈 차단)**: 명령 성공 응답이 flush 이전 노출 / 공백지 건국·임관 false-deny / 외교 명령 성공처럼 보이나 diplomacy row 불변 / 버튼이 실제 daemon 결과 없이 완료처럼 보임 / B1이 같은 월·명령 반복 중단인데 원인 기록 없음 / engine JUnit 녹색이나 XML system-out에 예상치 못한 turn-daemon-loop tick failed·JDBC 오류·종료 순서 역전 / seed 중간 실패 후 부분 world_state로 재시도 멱등 skip / v2 migration·schema가 s1 production world 오염.

---

## 문서 간 중복/충돌 지점

1. **페이즈 넘버링 3중 체계 (가장 중요한 충돌).** 06-28은 `V2-0~V2-5`, 06-29는 `V2-P0~V2-P8`, 07-13은 `B0/B0.5/B1/B2` + `V2-0A/V2-G0/V2-0B/V2-1`을 쓴다. **정본 판단**: 06-28은 헤더에 `superseded-by-2026-07-12-product-spec` 명시 → 폐기. 06-29는 그 superseded된 06-28을 base로 하고 supersession 표기는 없으나 작성일이 앞선다. 07-13(active-plan, 최신 작성일 2026-07-13)이 게이트·오픈 순서의 최신 정본으로 보이며, 제품 사양의 정본은 06-28이 지목한 `2026-07-12-opensamguk-v2-product-spec.md`(4개 대상 밖). 즉 **06-29의 V2-P* 상세는 개념·작업 목록으로 유효하되, 오픈 게이팅 순서는 07-13이 이긴다.**

2. **가신/어전회의/봉토 = 같은 3개 시스템, 다른 넘버.** 06-28 V2-2/V2-3/V2-4 ↔ 06-29 V2-P5/V2-P6/V2-P7. 스키마 테이블명(`general_retainers`, `court_councils`, `bias_profiles`, `subfactions`, `fiefs`, `feudal_contracts`)이 두 문서에서 동일 → 충돌 아님, 06-29가 더 상세한 실행판.

3. **전쟁 replay phase 목록 불일치.** 06-28: approach/encounter/field/siege/aftermath(5). 06-29 V2-P4: approach/scout/intercept/field/siege/urban/aftermath(7). → 06-29(후속)가 encounter를 scout/intercept로, +urban으로 세분한 개정. 07-13 identity 문서는 전투 phase를 다루지 않음(다른 축).

4. **첫 착수점 상충.** 06-28 §13은 "코딩이 아니라 schema spike 먼저". 06-29 §5는 "V2-P0 안정화부터 착수 + schema spike 병행, 첫 코드=조작 대상 패널+KST". 07-13은 "V1 안정화 게이트(B0/B0.5 완료)가 먼저, 그 다음 V2-0A 격리". → **07-13이 최신 착수 순서. v2 코드는 V2-0A production 격리 게이트 통과가 선행 조건.**

5. **시간/순(상·중·하순) 처리.** 06-28 §8.1은 상·중·하순을 v2 신규 시간 표현으로, simulation tick/general turn/game date 3레이어로 분리. 06-29 V2-P0·07-13은 상·중·하순을 v1 운영 표시로 보고 "월 이벤트 3회 실행" 버그를 월 경계+순 이벤트로 분리하는 안정화 과제로 다룸. → 상충이라기보다 06-28은 v2 신규 개념, 06-29/07-13은 v1 버그 수정으로 층위가 다름. 실행상 07-13/06-29의 v1 안정화가 선행.

6. **3D 지위.** 06-28 원문은 3D를 강조 안 했으나 2026-07-14 정정으로 3D를 v2 기본 지도·전장 surface로 확정. 06-29는 이미 V2-P0에 3D foundation proof, 07-13은 V2-G0 3D gate를 둠. → 세 문서 모두 최종적으로 3D=기본 surface로 수렴(정정 이후 정본).

7. **국가 정체성(07-13 identity) vs 조작 대상/부곡/작전(06-29).** 서로 다른 축이라 직접 충돌은 없음. 단, 07-13 identity는 canonical 명령 id 정본을 `2026-07-12-v2-command-catalog-and-rollout.md`로 지목 → 명령 카탈로그는 별도 정본 문서(4개 대상 밖)가 존재한다는 신호.

---

## 참고 (4개 대상 밖이지만 정본으로 지목된 문서)
- `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md` — 06-28이 지목한 후속 정본(제품 사양).
- `docs/superpowers/specs/2026-07-12-v2-command-catalog-and-rollout.md` — 07-13 identity가 지목한 명령 canonical id/payload/authority 정본.
- `docs/superpowers/research/2026-07-14-samnet-live-play-reverse-design.md` — 06-28 정정이 지목한 3D 확정 최신 근거.

티켓화 시 이 3개를 함께 읽어야 명령 id·제품 사양·3D 계약이 확정된다(현재 분해는 4개 대상 문서 범위 내에서만 수행).