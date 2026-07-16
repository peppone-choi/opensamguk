# v2 로드맵 문서 4종 — 마이크로 티켓 재분해

지시 갱신 반영: 모든 티켓을 반나절 이하·PR 1개·파일 몇 개 수준으로 재분해했다. 문서가 세부(식·스키마·임계값·연결점)를 주지 않는 지점은 `[추가 분해 필요: 사유]`로 표기했다. 완료 기준이 문서에 없으면 `[완료: 미명시]`. 정본 판정·비범위는 직전 반환과 동일하게 유지(문서 끝에 재수록).

표기: `[Dx-NN] 제목 — 요약. 출처. [선행] [완료] [추가 분해 필요]`. 스키마 티켓 1개 = 테이블/타입 1개. 검증 bullet 각각은 별도 테스트 티켓 후보로 봐도 된다.

---

## 문서 1 — `2026-07-13-v2-nation-identity-rework.md`
요약: v1 15개 국가타입을 배타적 enum 확장 없이 5요소(정통성/통치형태/전통/조직망/정책) 조합으로 재해석, v1 효과는 동결. 첫 슬라이스는 유가·태평도·도적 3개. 구조는 §3 데이터모델→§4 게임플레이 계약→§5~6 리워크→§7 명령→§8 외교→§9 변화규칙→§10 AI→§11 슬라이스.

### 데이터 모델 (§3.4/§3.1/§3.2/§3.3)
- [D1-01] FactionIdentityProfile 데이터 클래스 정의 — factionId·stage·legitimacyByAudience·governanceForms·traditions·activePolicies·institutionalTensions·contentProfile·version. §3.4. [완료: 미명시]
- [D1-02] IdentityPreset 데이터 클래스 골격 정의 — id·canonicalName·explanatorySubtitle·legacyCode·initialStage·provenance. §3.4. [완료: 미명시]
- [D1-03] IdentityPreset 시드 필드 정의 — legitimacySeeds/governanceSeeds/traditionSeeds/startingNetworks. §3.4. [선행: D1-02] [완료: 미명시]
- [D1-04] IdentityPreset capability 필드 정의 — command/facility/recruitment/diplomacy Capabilities. §3.4. [선행: D1-02] [완료: 미명시]
- [D1-05] transitionRules 필드 정의 — §3.4. [추가 분해 필요: 규칙 구조 미명시, §9는 절차만]
- [D1-06] institutionalTensions 필드/타입 정의 — §3.4. [추가 분해 필요: 내부 구조 미명시]
- [D1-07] contentProfile 값(CHRONICLE/CLASSIC/LEGACY) 타입 정의 — §3.4/§2. (D1-49 연결)
- [D1-09] LegitimacySource(청중) enum 6값 정의 — HAN_COURT/LOCAL_ELITES/COMMONERS/RELIGIOUS_ADHERENTS/MILITARY_FOLLOWERS/FRONTIER_COMMUNITIES. §3.1.
- [D1-10] legitimacyByAudience 맵 profile read/write 배선 — §3.1/§3.4. [선행: D1-01,D1-09]
- [D1-11] 조정 책봉 시 벡터 변경 훅(과거 조직망 미소멸) — §3.1. [추가 분해 필요: 책봉 이벤트 연결점 미명시]
- [D1-12] GovernanceStage enum 5값 정의 — MOVEMENT/CONFEDERATION/TERRITORIAL_REGIME/BUREAUCRATIC_STATE/DYNASTIC_CLAIM. §3.2.
- [D1-13] 문서 명시 initialStage 3케이스 매핑(태평도=MOVEMENT, 흑산형 도적=CONFEDERATION, 한 군현 인수 유가·법가=TERRITORIAL_REGIME/BUREAUCRATIC_STATE) — §3.2. 나머지 프리셋 매핑은 각 프리셋 티켓.
- [D1-14] NetworkPresence 필드 11종 정의 — networkId/placeId/visibility/adherents/leadership/cohesion/supplies/localSupport/hostNationRelation. §3.3.
- [D1-15] visibility enum PUBLIC/COVERT/SUPPRESSED 정의 — §3.3.
- [D1-16] 도시/통행로에 network 부착 저장 구조 — §3.3. [추가 분해 필요: 스키마·영속 방식 미명시]
- [D1-17] 점령 시 행정/군사/조직망 장악 상태 분리 표현 — §3.3. [추가 분해 필요: 상태 모델 미명시]

### 명령 등록 (§7, payload 정본은 07-12 카탈로그)
- [D1-18~20] 개인턴 명령 각 등록: personal.network.establish / operate / negotiate. §7. [완료: 미명시]
- [D1-21] chief.identity.convene 등록. §7.
- [D1-22] chief.identity.adopt 등록. §7.
- [D1-23] chief.identity.appoint 등록. §7.
- [D1-24] chief.identity.proclaim 등록. §7.
- [D1-25] chief.identity.suppress 등록. §7. [추가 분해 필요: 잠복·이탈·반발 계산 로직 미명시]
- [D1-26] chief.identity.accommodate 등록. §7.
- [D1-27] polity.transition.resolve 등록. §7. (D1-41 연결)

### 외교 조항 (§8, 각 타입 정의 + 엔진 슬롯)
- [D1-28~36] 조항 각 정의: RecognitionTerm / AutonomyTerm / ReligiousTolerance / SafePassageTerm / TributeTerm / HostageTerm / AmnestyTerm / GarrisonTerm / NetworkDisclosureTerm. §8. [추가 분해 필요: 조항별 효과 로직 미명시]

### 정체성 변화 파이프라인 (§9)
- [D1-37] convene가 개혁 의제 개시. §9-1. [선행: D1-21]
- [D1-38] 청중/조직망/관료/장수 찬반 세력 산출. §9-2. [추가 분해 필요: 산출식 미명시]
- [D1-39] 개인턴 설득/감찰/진압/조정/시설 전환 연결. §9-3. [추가 분해 필요]
- [D1-40] 정책·임명 일치 기간 판정. §9-4. [추가 분해 필요: 기간·판정식 미명시]
- [D1-41] transition.resolve 채택/타협/분열/반란 확정. §9-5. [추가 분해 필요: 결과 결정식 미명시]
- [D1-42] 이전 조직망 야당/잠복/자치 잔존 처리. §9-6. [완료(§11): 과거 조직망·반대 세력 보존]
- [D1-43] 선언·행동 불일치 시 청중 정통성 하락. §9 말미. [추가 분해 필요]

### AI (§10)
- [D1-44] utility 9항 합산 골격 구현. §10.
- [D1-45] 각 항(survival/legitimacyAudienceFit/networkContinuity/resourceFeasibility/militaryRisk/treatyReliability/institutionalPromiseFulfilment/factionResistance/overextension) 계산. §10. [추가 분해 필요: 각 항 식 미명시 → 9개 하위 티켓]
- 프리셋별 가중은 각 프리셋 티켓에 귀속(§10 예시: 태평도/도적/유가/법가/병가/종횡가).

### 동결/고증 게이트
- [D1-47] v1 NationTypeRegistry 동결 회귀 게이트. §2/§11. [완료: v1 테스트·골든 byte 불변]
- [D1-48] LEGACY 월드만 NationTypeModule 사용하도록 분기. §2. [추가 분해 필요: 월드 분기점 미명시]
- [D1-49] CHRONICLE 금지 4항목(묵가 기사단/승병/기상마법/완성 태평도 관료국) 생성 차단 게이트. §11/§12. [완료: 4항목 미생성]
- [D1-50] 콘텐츠 행별 provenance 구분(사료/학술복원/연의/게임) 강제. §6/§12. [추가 분해 필요: 검증 방식 미명시]

### 슬라이스 프리셋 — 유가 (§6.10)
- [D1-51] 유가 사령턴 6종 배선(학교 설치/효렴·현량 추천/국가 의례/관직 서열 정비/상복·제사 재판/조서 봉대).
- [D1-52] 유가 개인턴 4종(경학 강론/인재 천거/향론 조사/예제 집행).
- [D1-53] 유가 도시 시설 5종(학교/문서고/관아/사당/객관).
- [D1-54] 유가 병력 효과(추천·관직 수용·향리 동원·항복 관료 재등용). [추가 분해 필요: 수치 미명시]
- [D1-55] 유가 외교/점령 조항 매핑(작위·명분·의례서열·황제보호·조공).
- [D1-56] 유가 긴장(추천 독점·문벌화·개혁 저항). [추가 분해 필요]
- [D1-57] 유가 AI 가중(책봉·명사·학교·계승 질서). §10/§6.10. [추가 분해 필요]
- [D1-58] 유가 전환 규칙(법가/명가/덕가 혼합, 병가 갈등).
- [D1-59] 유가 학교 파괴 손실 검증 테스트(관료 후보·명사 관계·정통성). [완료(§11)]

### 슬라이스 프리셋 — 태평도 (§6.13)
- [D1-60] initialStage=MOVEMENT + 도시별 COVERT 方 network 시드.
- [D1-61] 사령턴 6종(대현량사 포고/방 거수 임명/동시 거병일/황천 선포/신도 구휼/사면·포교 협상).
- [D1-62] 개인턴 6종(포교/치병·자복 의례/방 조직/표식·연락망/은닉 군량/거수 조정).
- [D1-63] 도시 조직(치병·집회소/방 연락망/신도 은닉처/이동 집단). [추가 분해 필요]
- [D1-64] 군대(대규모 신도·가족 동원, 초기 장비·수송·공성·지휘 약점, 거수 독자 지휘권). [추가 분해 필요]
- [D1-65] 경제 구분(신도 기여/지역 비축/몰수·약탈/이동 생산/정착 편입). [추가 분해 필요]
- [D1-66] 외교/점령 조항 매핑(포교 허용/신도 사면/지도자 처우/무장 해제/자치/책봉).
- [D1-67] 전환(종교운동→동시봉기→연맹정권→교단국/혼합국/귀순 편입). [추가 분해 필요]
- [D1-68] 연의 계층 ROMANCE_ATTESTED 게이트(남화노선·천서·초자연 기상). §6.13.
- [D1-69] 동시 존재 검증 테스트(공식 소유국+태평도 조직망). [완료(§11)]

### 슬라이스 프리셋 — 도적 (§6.3)
- [D1-70] initialStage=CONFEDERATION + 비도시 산채/route 시드.
- [D1-71] 사령턴 6종(연맹 소집/통행세 요구/보호비 협상/공동 약탈 목표/조정 귀순·책봉 요청/수령 자치 보장).
- [D1-72] 개인턴 5종(산채 설치/장물 시장 연결/길목 매복/유민·타 산채 포섭/인질·포로 교환).
- [D1-73] 지도 조직(산채/비밀 창고/장물 시장/산길 연락망) + route control. [추가 분해 필요]
- [D1-74] 군대(소두목 수행대/기습·정찰·험지/장비·공성·보급 약점). [추가 분해 필요]
- [D1-75] 경제(통행세·보호비·장물·피난민 노동·후원자, 전리품 분배 위반 시 결속 붕괴). [추가 분해 필요]
- [D1-76] 외교/점령 조항 매핑(안전통행·몸값·조공·명목 귀순·관직·산지 자치).
- [D1-77] 전환(산채 연맹→조정 공인 연맹→군정→관료국).
- [D1-78] 도적 AI(약한 주둔지·통행로·전리품·귀순 비교). §10. [추가 분해 필요]
- [D1-79] 도시 없는 존속 + 책봉 미삭제 검증 테스트. [완료(§11)]

### 나머지 프리셋 / 특수 상태
- [D1-80] 나머지 10개 프리셋 인스턴스화 — 덕가§6.1/도가§6.2/명가§6.4/묵가§6.5/법가§6.6/병가§6.7/불가§6.8/오두미도§6.9/음양가§6.11/종횡가§6.12. 각 프리셋마다 슬라이스와 동일한 9-surface(사령턴/개인턴/도시/군대/외교·점령/긴장/AI/전환/검증)를 §6.x 상세대로 전개. [선행: D1-59/69/79] [완료: 미명시] [추가 분해 필요: 프리셋별 9개 하위 티켓 = 90개로 전개; 각 §6.x가 사양]
- [D1-81] 중립: 첫 건국 회의서 제도1+정책1 선택 흐름. §6.14. [추가 분해 필요: 선택 UI/로직 미명시]
- [D1-82] 중립: 장기 미선택 시 관료 공백·소두목 경쟁·규칙 불일치 발생. §6.14. [추가 분해 필요]
- [D1-83] 없음(None): None 상태 사령 명령 validation 실패. §6.15. [완료: validation 실패]
- [D1-84] 재야/건국 후보/멸망 잔존을 Polity/Movement 상태로 분리. §6.15. [추가 분해 필요: 상태 정의 미명시]

### 비범위 (§1/§2/§9/§11/§4)
v1 ActionNationType 효과·수치·골든 변경 / 15개 배타적 enum 재통합 / 드롭다운 즉시 성향 변경 / CHRONICLE에서 묵가 기사단·승병·기상마법·완성 태평도 관료국 역사화 / 변경 시 과거 조직망·반대 세력 삭제 / 수치 보정이 고유 플레이 주체가 되는 것.

---

## 문서 2 — `2026-06-28-...game-design-plan.md` ⚠️ superseded (`superseded-by-2026-07-12-product-spec`)
요약: v2 별도 제품 방향(전장 replay + 가신·추종·봉신 + 도독부/봉건), 런타임 LLM 없음. 아래 티켓은 06-29(D3-*)가 재상술하므로 **개념 출처로만**, 실행은 D3 우선. 3D=기본 surface는 2026-07-14 정정으로 유효.

### V2-0 문서·증거 고정 (Exit: myosam-help-corpus.md 갱신 + schema spike 문서)
- [D2-01] myosam 37p 위키 컴파일 · [D2-02] samnet feature snapshot · [D2-03] v2 ERD 초안 · [D2-04] v1/v2 브랜치 분리 원칙 문서화 · [D2-05] 3D foundation proof(도시3·route2·terrain1·formation4)+camera/picking/performance 계약.
### V2-1 replay spine (Exit: seed 기반 deterministic replay body+hash, 결과 로그·replay 연결)
- [D2-06] battle_replays schema · [D2-07] battle_replay_phases schema · [D2-08] 공격 명령 1개가 replay 생성 · [D2-09] phase 5종(approach/encounter/field/siege/aftermath) · [D2-10] UI replay timeline read-only.
### V2-2 가신 1명 (Exit: 근거 score 저장, 승인/거부가 신뢰/충성 영향)
- [D2-11] general_retainers · [D2-12] retainer_proposals · [D2-13] retainer_interactions · [D2-14] 참모 가신 1명 자동생성/영입 · [D2-15] 전쟁/내정/외교 1종 제안 · [D2-16] 승인→명령 큐/정책 연결.
### V2-3 어전회의 (Exit: seed 재현, UI 편향·근거 표시)
- [D2-17] court_councils · [D2-18] council_opinions · [D2-19] bias_profiles · [D2-20] 선전포고 의제 1종 · [D2-21] 참모/군수관/사신 의견 · [D2-22] 플레이어/NPC ruler AI 결정.
### V2-4 봉토·도독부 (Exit: 충성/자율성이 명령 지연 영향, 봉토 회수/보상)
- [D2-23] subfactions · [D2-24] fiefs · [D2-25] feudal_contracts · [D2-26] 도시1 봉토 · [D2-27] 세금/징병 일부 중앙 납부 · [D2-28] 원군 수락/지연/축소.
### V2-5 (Exit: fixture+수동 QA 재현)
- [D2-29] 동적 전쟁 첫 완성 통합 시나리오.
### 기타
- [D2-30] 06-28 domain schema spike 문서 작성(§13) · [D2-31] 시간 cadence 3레이어 분리 설계(§8.1) [추가 분해 필요].

### 비범위 (§12)
samnet/myosam 화면·자산 복제 / v1 goldens 변경 / 런타임 LLM 가신 발화 / 처음부터 전체 삼국지 지도 / 첫 MVP 모든 병종·건물·관직.

---

## 문서 3 — `2026-06-29-v2-release-implementation-plan.md` (execution-plan)
요약: 06-28을 실행 계획으로 상술. v1 운영면 유지 + 4대 차별점(조작 대상 UI/가신·추종·부곡 다중 주체/동적 전쟁/봉건 정치). phase 넘버링 V2-P0~P8은 07-13 게이트 체계와 별개. 각 phase의 작업 번호가 문서에 있으므로 그대로 마이크로 티켓화.

### V2-P0 v1 운영 안정화 (작업 §4, 검증 7개 = 완료 기준·각각 별도 테스트 티켓 후보)
- [D3-01] 서버/컨테이너 시간대 Asia/Seoul 고정.
- [D3-02] ServerClock UTC anchor/KST 표기 분리(또는 v2 clock 명시 zone).
- [D3-03] 로그인·로비·메인·정보·로그 전부 월·순 표시.
- [D3-04] 상/중/하순 월 이벤트 3회 실행 문제를 월 경계+순 이벤트 분리.
- [D3-05] 메인 UI 현재 장수 도시 기본, 맵 선택 도시 보조.
- [D3-06] 징병 UI 가능 병과 기본 노출+전체 토글.
- [D3-07] 명령 예약·서신·견문 예약표 반영+결과 표시 e2e 잠금.
- [D3-08] 3D foundation proof(도시3·route2·terrain1·formation4 동일 world coordinate+picking 계약).
- [D3-09] 임관 전후 e2e(소속·봉록·도시·국정 메뉴·개인 로그 동일 사건 + 관직·부대 역할별 capability).
- [D3-10] 명령 활성 4조건(actor capability/선택 장수/queue slot/server candidate set) 분리 + 거부 사유 API·UI 동일.
- 완료 기준(검증): production health·/game/s1 / 로그인→예약→표반영→데몬→결과로그 / web/game typecheck·build / command lifecycle tests / canvas·picking·camera·context-loss·FPS / 재접속 후 단일 사건 id / capability fixture(일반장수 회의실·등용장·부대창설 허용, 관직임명 거부, 부대장 전용 집합 거부).

### V2-P1 조작 대상 패널 (검증 5개 = 완료 기준)
- [D3-11] 메인 상단 조작 대상 영역 추가.
- [D3-12] 기본 대상=본인 장수.
- [D3-13] 도시/국가/장수 카드를 단일 현재 대상 상태 패널 통합 + 3D picking 연결.
- [D3-14] 커맨드 모달이 선택 대상 id/type 포함 내부 모델로 오픈.
- [D3-15] 서버 목적지 후보 API(권한·외교·인접성·보급 평가; 3D·텍스트 동일 candidate id).
- [D3-16] 미지원 대상 비활성 미노출.
- [D3-17] 가신 선택 공간을 같은 대상 selector에 흡수(placeholder). [추가 분해 필요: 가신 도입 전 훅]
- 완료 기준: 본인 장수 선택 시 기존 예약 동작 / 도시 선택은 inspect·focus만·명령 선택 후 목적지 초안 승격 / capability 권한별 차등 / queue slot 미선택 vs actor 권한 부족 reason code 구분 / 36턴 표 모바일·데스크톱 미파손.

### V2-P2 부곡 foundation (검증 3개 = 완료 기준)
- [D3-18] general_bugok schema spike+migration.
- [D3-19] 기존 장수 병력을 기본 부곡 1개로 materialize.
- [D3-20] 부곡 위치/병종/병력/훈련도/사기/보급 read API 노출.
- [D3-21] UI 부곡 탭 추가.
- [D3-22] 명령은 기존 병력 값 사용하되 read 모델 부곡 병행.
- [D3-22b] 후속 테이블 bugok_orders/bugok_movements/bugok_supply_state/operation_bugok_assignments. [추가 분해 필요: 각 테이블 스키마 미상술]
- 완료 기준: 기존 전투/징병 미파손 / 장수 병력=기본 부곡 병력 / 위치 분리 fixture 존재.

### V2-P3 작전·협공 (검증 3개 = 완료 기준)
- [D3-23] operations schema · [D3-24] operation_participants schema · [D3-25] operation_routes schema.
- [D3-26] 작전 생성 UI(목표 도시·참여 대상·경로·도착 순).
- [D3-27] 기존 출병을 단독 작전 1개로 wrapping.
- [D3-28] 다수 장수/부곡 협공 판정.
- [D3-29] 도착 window·경로 충돌·인접 도시 동시 압박 계산. [추가 분해 필요: 계산식 미명시]
- [D3-30] 협공 성공/실패 replay phase 기록.
- [D3-31] operation_routes 3D route corridor 표시 + 집결/교전 anchor 전술 seed 전달.
- 완료 기준: 같은 seed·입력 같은 replay / 3 fixture(단독·2부대협공·원군지연) / 협공 로그 월·순 포함.

### V2-P4 동적 전쟁 replay (검증 3개 = 완료 기준)
- [D3-32] battle_replays schema · [D3-33] battle_replay_phases schema.
- [D3-34] phase 7종(approach/scout/intercept/field/siege/urban/aftermath) 정의.
- [D3-35] 요격/농성/공성/지형/함정/부곡 보급을 phase input 기록.
- [D3-36] UI timeline read-only.
- [D3-37] 정세 로그와 replay 연결.
- [D3-38] replay가 terrain/spatial snapshot 재구성 + 카메라 keyframe 선택 재생. [추가 분해 필요]
- 완료 기준: 전투 후 replay 조회 / phase별 근거 JSON / 월·순 로그와 timestamp 일치.

### V2-P5 가신 1명 slice (검증 3개 = 완료 기준)
- [D3-39a] general_retainers schema · [D3-39b] retainer_proposals schema · [D3-39c] retainer_interactions schema.
- [D3-40] 본인 장수에 참모 가신 1명 생성.
- [D3-41] 전쟁/내정/외교 중 1개 제안 rule score 생성. [추가 분해 필요: score 식 미명시]
- [D3-42] 제안을 조작 대상 패널 가신 슬롯 표시.
- [D3-43] 승인 시 작전 초안/커맨드 예약 연결.
- [D3-44] 거부 시 신뢰/충성/냉각시간 변화.
- 완료 기준: 런타임 LLM 없음 / seed 재현 / 승인·거부 저장+UI.

### V2-P6 어전회의 (검증 3개 = 완료 기준)
- [D3-45a] court_councils · [D3-45b] council_opinions · [D3-45c] bias_profiles schema.
- [D3-46] 선전포고/대규모 침공 의제 1종.
- [D3-47] 참모/군수관/사신/도독부 대표 의견 생성.
- [D3-48] 찬성·반대·보류 + 확신 + 근거 + 편향 저장.
- [D3-49] 플레이어 결정을 작전/명령 큐 변환.
- 완료 기준: 같은 seed 같은 의견·결론 / 편향 UI / 결정 후 작전 초안 생성.

### V2-P7 도독부·봉토 (검증 3개 = 완료 기준)
- [D3-50a] subfactions · [D3-50b] fiefs · [D3-50c] feudal_contracts schema.
- [D3-51] 도시1 봉토 부여.
- [D3-52] 봉토가 세금/징병/부곡 일부 보유.
- [D3-53] 중앙 원군 요청 수락/지연/축소 룰. [추가 분해 필요: 룰 미명시]
- [D3-54] 봉신 충성/자율성이 작전 결과 반영. [추가 분해 필요]
- 완료 기준: 봉토 수입 일부 중앙 납부 / 원군 결과 replay / 회수·보상 가능.

### V2-P8 출시 슬라이스
- [D3-55] 통합 시나리오(10단계) QA — 각 단계는 선행 phase 티켓에 귀속. 완료 기준: production 수동 QA / 두 세션 동기화 / replay·커맨드 조회 / KST / 월·순 로그 / 3D desktop·mobile gate.

### 기타 (§5/§3.5)
- [D3-56] 06-29 domain schema spike 문서 작성.
- [D3-57] historical_profiles schema · [D3-58] general_relationships schema · [D3-59] scenario_placement_rules schema · [D3-60] legitimacy_events schema.
- [D3-61] 비역사 선택 시 관계/명분/충성/민심 비용. [추가 분해 필요: 비용식 미명시]

### 비범위 / 출시 보류 (§1/§2.1/§6)
복제 금지(samnet/myosam 화면·자산·수치·명칭, 33병종 세부수치). 보류: 예약 미반영·데몬 결과 관측 불가 / 서신·견문 결과 미출력 / 월·순 로그 누락 / 월 이벤트 3회 실행 / production 로그인→진입→예약→결과 실패 / 가신·회의가 LLM 의존 / 작전·replay seed 재현 불가.

---

## 문서 4 — `2026-07-13-v1-stabilization-and-v2-open-plan.md` (active-plan)
요약: v2 오픈 전 v1 운영 기준선 고정. B0/B0.5/B1(12개월·천통)·표적·logic/engine gate·agent-system은 2026-07-13 녹색. 아래는 열린 작업만 마이크로 티켓화(완료 항목 제외).

### 실행순서 3 — v1 안정화 체크리스트 (항목별 명령·판정 기준 문서화)
- [D4-01] seed · [D4-02] load · [D4-03] intake · [D4-04] flush · [D4-05] read · [D4-06] SSE · [D4-07] deploy — 각 항목 명령+판정 기준 1개씩. [완료: 항목별 문서화]

### B1b 자동외교 후속 (완료: 자동외교 표 상태 전이)
- [D4-08] che_종전제의 상태전이 · [D4-09] che_종전수락(war→trade).
- [D4-10] che_불가침제의 · [D4-11] che_불가침수락(non-aggression 상태·term 기록).
- [D4-12] che_불가침파기제의 · [D4-13] che_불가침파기수락(trade 복귀 후 선전포고 가능).

### B2 운영 스모크
- [D4-14] s1/QA 60초 cadence 축소 루프 구성.
- [D4-15] Redis intake·daemon·read·SSE 관측 배선.
- [D4-16] no-op/false-deny/stale frontend 탐지. [추가 분해 필요: 탐지 기준 미명시]
- [D4-17] seed 중간 실패 후 world_state만 남아 재시도 skip 안 되는지 검증(importAll 원자성 오픈 리스크). [완료: 재시도 미skip]

### 실행순서 7 — V2-0A production 격리 게이트
- [D4-18] v2 route 0 · [D4-19] v2 bean 0 · [D4-20] v2 migration 0 · [D4-21] v2 catalog loader 0 · [D4-22] v1 schema/seed/golden diff 0. (각 = 게이트 단언 1개)

### 실행순서 8 — V2-G0 역사 지리·3D 공간 계약
- [D4-23] 140년 baseline→189년 delta 검증 · [D4-24] activation manifest 검증 · [D4-25] synthetic catalog 2,000개 3D gate · [D4-26] 실제 catalog 2,000개 3D gate. [추가 분해 필요: baseline/delta/manifest 데이터 정의는 별도 문서]

### 실행순서 9 — V2-0B sandbox runtime
- [D4-27] ACTIVE catalog만 sandbox 적재 · [D4-28] sandbox↔production 독립 검증. [선행: D4-18~26]

### 실행순서 10 — V2-1 첫 수직 slice
- [D4-29] command result lifecycle 화면 관측 · [D4-30] 조작 대상 갱신 화면 관측(턴 완료 무관). [선행: D4-27]

### 배포 전 체크리스트 (Go 조건)
- [D4-31] image tag · [D4-32] seed code · [D4-33] runner health · [D4-34] disk headroom · [D4-35] DB migration 상태 — 각 확인 1개씩.

### 비범위 / 차단 (구현 원칙 · No-Go)
구현 원칙: 억지 종착상태(직접 isunited/소유권 조작) 금지 / 국가 최소5·기본6 / 장수 기본60(국가당10) / 내정 캐릭터 치안·성벽·수비·기술 분화 / 자동외교=전략 입력 / 모든 역할군이 전체 공개 커맨드 선택 가능 / 모든 행동 실제 핸들러 통과(helper가 world row 직접 완성 금지) / **v2는 B0·B0.5 깨면 중단, B1은 오픈 전 hardening gate 승격**. No-Go 8종: flush 이전 성공 노출 / 건국·임관 false-deny / 외교 성공처럼 보이나 diplomacy row 불변 / 버튼이 daemon 결과 없이 완료처럼 / B1 반복 중단 원인 미기록 / XML에 예상외 tick failed·JDBC 오류·종료 역전 / seed 중간 실패 후 부분 world_state 멱등 skip / v2 migration이 s1 production 오염.

---

## 문서 간 중복/충돌 · 정본 판정 (직전과 동일, 유지)
1. **페이즈 넘버링 3중 체계.** 06-28 V2-0~5 / 06-29 V2-P0~P8 / 07-13 B0·B0.5·B1·B2 + V2-0A·G0·0B·1. 06-28은 헤더에 `superseded-by-2026-07-12` 명시 → 폐기. 06-29는 그 superseded된 06-28 base, supersession 표기 없으나 작성일 앞섬. **07-13(active-plan, 최신)이 게이트·오픈 순서 정본**, 제품 사양 정본은 `2026-07-12-opensamguk-v2-product-spec.md`(대상 밖). → D3-* 상세는 개념·작업으로 유효, 오픈 게이팅은 D4-*(07-13)가 이긴다.
2. **가신/어전회의/봉토 동일 시스템, 다른 넘버.** D2-2/3/4 ↔ D3-P5/6/7, 테이블명 동일 → 충돌 아님, D3가 상세판.
3. **replay phase 불일치.** D2 5종 vs D3 7종(encounter→scout/intercept, +urban) → D3가 개정.
4. **첫 착수점.** 06-28 "schema spike 먼저" / 06-29 "V2-P0 안정화+spike, 첫 코드=조작 대상 패널+KST" / 07-13 "B0·B0.5 완료→V2-0A 격리". → **07-13 순서가 정본, v2 코드는 V2-0A(D4-18~22) 통과가 선행.**
5. **상·중·하순.** 06-28 §8.1 v2 신규 3레이어 vs 06-29·07-13 v1 "월 이벤트 3회" 버그 수정(D3-04). 층위 차이, v1 안정화 선행.
6. **3D.** 06-28은 2026-07-14 정정으로 3D=기본 surface 확정, 06-29(D3-08)·07-13(D4-23~26) 수렴 → 정정 이후 정본.
7. **정체성 vs 조작 대상/부곡/작전.** 다른 축, 직접 충돌 없음. identity가 명령 id 정본을 `2026-07-12-v2-command-catalog-and-rollout.md`로 지목.

## 참고 (대상 밖·정본 지목 3종)
`2026-07-12-opensamguk-v2-product-spec.md`(제품 사양) · `2026-07-12-v2-command-catalog-and-rollout.md`(명령 canonical id/payload/authority) · `research/2026-07-14-samnet-live-play-reverse-design.md`(3D 확정 근거). 티켓화 확정 전 함께 읽어야 D1 명령 payload·D2/D3 replay·3D 계약이 고정된다.