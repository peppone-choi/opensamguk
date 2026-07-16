# v2 두 문서 최소 단위(반나절·PR 1개) 티켓 분해

읽기 전용으로 처리, 저장소 변경 없음. 반복되는 산출물 분해는 그룹별 표준 템플릿으로 1회 정의하고, 각 커맨드/항목은 고유 ID+제목=티켓 1개로 열거. 문서가 개별 세부(precheck·RNG·로그·수치)를 미명시한 지점은 **추가 분해 필요**로 표기.

## 공통 산출물 분해 템플릿
**[T-CMD] 신규 커맨드**(문서1 §2+§12): (a)스키마·등록 / (b)authority·precheck(지정 resolver) / (c)adapter·상태변이·부수효과 / (d)domain·replay event / (e)테스트(golden 또는 deterministic replay + rejection/rollback) / (f)FE submit(mutation UI 있으면). → (b)~(e) 구체 판정은 커맨드마다 **추가 분해 필요**.
**[T-LEG] 레거시 어댑터**(문서1 §3/§4, v1 불변): (a)canonical id+alias adapter 등록(parityStatus=LOCKED/ADAPTED) / (e)v1 golden 불변 회귀. → (b)~(d) 신규 로직 없음.
**[T-ENTRY] 콘텐츠 엔트리**(문서2 §3.6): SL-1 NAMED(ContentEntry 생성+CatalogBudgetSlot CONSUMED, 양방향 참조) / SL-2 CLAIMED(claimIds[]+EvidenceRef) / SL-3 FIXTURE_GREEN(모집·보급·AI/플레이어 공통 판정 fixture) / SL-4 ACTIVE(승인 pack 편입). → C2.1의 7개 외 SL-2·SL-3 세부는 항목마다 **추가 분해 필요**.
**[T-FAC] 시설 엔트리**(문서2 §6/§4): (a)Facility 정의+SettlementStatus/SeatAssignment 분리 / (b)"새로 여는 능력"을 FacilityCapabilityResolver 연결(presence-grant 금지) / (c)ResourceNode·route·staff·office 배선 / (e)ALLOW/DENY fixture. → 세부 수치 **추가 분해 필요**.

---

# 문서 1 — 2026-07-12-v2-command-catalog-and-rollout.md
목적: v2 커맨드 정본(reviewed-command-source-of-truth). 4계층(PERSONAL/CHIEF/STRATEGIC/TACTICAL) + 공통 계약(메타데이터·8 typed resolver·실행 상태기계) + 계층별 카탈로그 + C0~C5 롤아웃·게이트. 핵심 불변식: 개인 che_출병이 사령 승인 없이 침공 Operation 생성, 사령턴은 열린 전선에만 정책; 전략/전술이 개인·사령 저장 링 오염 금지.
롤아웃(이름·순서 보존): **C0 카탈로그 동결 → C1 v1 facade+비활성 adapter registry → C2 전략 상태·건설 → C3 전술 엔진 → C4 전장·캠페인 연결 → C5 확장·폐기**. §11 최종 실행 7단계. 삭제기계 ACTIVE→HIDDEN_FROM_NEW_UI→DEPRECATED→REMOVED. 실행기계 DRAFT→VALIDATING→REJECTED/ACCEPTED→EXECUTING→RESOLVED/EXPIRED/CANCELLED.
카탈로그 총수: 개인턴 legacy 46+신규 21 / 사령턴 legacy 24+신규 25 / 전략 domain 17 / 전술 14. **총 티켓 약 207개.**

**A. Foundation(§2,§10.1) 15티켓** — D1-F1 커맨드 catalog entry 메타데이터 스키마(9필드); D1-F2a~h authority resolver 8종(LEGACY_OFFICER_LEVEL/SUBJECT_OWNER/OPERATION_ROLE/OFFICE_ASSUMPTION/OFFICE_CAPABILITY[officer_level fallback 금지]/COURT_AUTHORITY/POLITY_ROLE/SYSTEM_RESOLVER); D1-F3 실행 상태기계(precheck 실패=EXECUTING 전 REJECTED); D1-F4a 개인/사령 result(requestId·turnIdx·actorId·commandCode·arg·reason·logEntryId, JDBC flush 후 공개) / F4b 전략 result(operationId/projectId+권한·선행·영향범위) / F4c 전술 result(battleId·formationId·sequence·issuedAtTick·expiresAtTick·clientCommandId, ack와 event 분리); D1-F5 정규화 파이프라인; D1-F6 alias registry(v1 실행기 재작성 금지).

**B. C0 카탈로그 동결(§8 C0/§11-1) 3티켓** — D1-C0-1 availableGeneral/ChiefCommand→CSV/JSON 추출; -2 각 legacy code에 layer·sourceRing·owner·adapter·parityStatus 매핑; -3 개인/사령 예약 API 다른 링 테스트 고정.

**C. 개인턴 legacy adapter(§3) 46티켓 [T-LEG]** — 공통완료: legacy 제거 없이 canonical adapter, v1 판정·로그·RNG·result 불변.
· 개인(10): 휴식, che_요양, che_단련, che_숙련전환, che_견문, che_은퇴, che_장비매매, che_군량매매, che_내정특기초기화, che_전투특기초기화.
· 내정(8): che_농지개간, che_상업투자, che_기술연구, che_수비강화, che_성벽보수, che_치안강화, che_정착장려, che_주민선정 → 각 personal.cityAction(CityProject와 별개, v1 내정 대체 금지).
· 군사(8): che_징병·che_모병→personal.recruit; che_훈련·che_사기진작→personal.retinue.prepare; che_출병→personal.sortie(v1 불변); che_집합·che_소집해제→personal.formation.organize/retinue.relocate; che_첩보(매핑 추가 분해 필요).
· 인사(8): che_이동·che_강행→formation.organize/retinue.relocate; che_인재탐색·che_등용·che_귀환·che_임관·che_랜덤임관·che_장수대상임관(매핑 추가 분해 필요).
· 계략(4): che_선동, che_탈취, che_파괴, che_화계(매핑 추가 분해 필요).
· 국가(8): che_증여, che_헌납, che_물자조달, che_하야, che_거병, che_건국, che_선양, che_해산 → 개인턴 원천 유지, 국가 명령 재분류 금지.

**D. 개인턴 v2 신규(§3) 21티켓 [T-CMD]** — personal.sortie/recruit/retinue.prepare/formation.organize/retinue.relocate/cityAction/cityProject.execute/cityProject.pause/network.establish/network.operate/network.negotiate/office.petition/office.accept(이상 SUBJECT_OWNER), office.assume(OFFICE_ASSUMPTION), office.exercise(OFFICE_CAPABILITY), office.selfStyle(SUBJECT_OWNER), court.audience/court.assentEdict/court.carryEdict/court.escort(COURT_AUTHORITY), reform.pilot(OFFICE_CAPABILITY). 개별 실행 세부 추가 분해 필요.

**E. 사령턴 legacy adapter(§4) 24티켓 [T-LEG]** — 공통완료: nation_turn 링·로그 유지, che_발령은 황제 관직 임명 아님, 전쟁 시작 슬롯 아님. 휴식; che_발령·포상·몰수·부대탈퇴지시; che_물자원조·불가침제의·선전포고·종전제의·불가침파기제의; che_초토화·천도·증축·감축; che_필사즉생·백성동원·수몰·허보·의병모집·이호경식·급습·피장파장; che_국기변경·국호변경.

**F. 사령턴 v2 신규(§4) 25티켓 [T-CMD]** — 공통완료: 전투 시작 금지, 계열별 policy 고정.
· diplomacy(polity-role): propose/declare/respond.
· operation(polity-role): policy/reinforcement/withdraw(열린 Operation 없으면 정책만 저장).
· build(polity-role): plan/assign.
· identity(polity-role): convene/adopt/appoint/proclaim/suppress/accommodate.
· office(office-nomination-policy→OfficeClaimResolver): nominate/assignOperationalRole/challengeClaim.
· court(court-protector-policy): petition/protect/relocate/proposeEdict/dispatchEdict.
· reform(reform-sponsor-policy→ReformAdoptionResolver): propose/expand/repeal.

**G. 전략 domain(§5) 17티켓 [T-CMD, event 필수]** — operation.create(subject-owner-sortie)/join/support/changeObjective(operation-commander)/setRetreat(operation-commander)/reinforce(assigned-formation-commander)/cancel(operation-owner); campaign.diplomacy.resolve(system-resolver-diplomacy); office.claim.resolve/office.jurisdiction.resolve; court.edict.resolve/edict.dispatch/edict.respond(recipient-polity-response)/succession.resolve; reform.adoption.resolve; polity.transition.resolve; campaign.battleResult.resolve. 공통완료: campaign.cityProject.*는 제출 커맨드 아님(domain event), 전술 화면 직접 실행 금지.

**H. 실시간 전술(§6) 14티켓 [T-CMD, result=F4c]** — battle.formation.move/face/change/hold/fire/charge/support/commitReserve(operation-role-reserve)/withdraw/rally/resupply(operation-role-supply); battle.command.delegate/revoke; battle.orderBatch(nested-order-authority, nested policy·sequence·expiry 개별 검증). 공통완료: BattleSession 열린 동안만·formation 단위.

**I. C1(§8 C1) 3티켓** — D1-C1-1 che_출병 개인턴 golden 보존 회귀 락; -2 personal.sortie→operation.create mapping+payload version만 등록(Operation 생성·adapter 활성화 금지); -3 chief 외교 nation_turn/로그 유지+DiplomacyPolicy read model 추가.

**J. C2(§8 C2/§11-4) 7티켓** — D1-C2-1 Operation / -2 OperationParticipant / -3 CityProject / -4 DiplomacyPolicy read model; -5 v2 sandbox/world profile에서만 che_출병 성공→operation.create(v1 production queue/result 불변); -6 v1/v2 sandbox world를 feature flag/world id 분리; -7 사령턴=정책·예산·원군·건설계획 vs 개인턴=출병·참여·현장실행 분리 검증.

**K. C3 전술 엔진(§8 C3) 14티켓** — 도메인6: D1-C3-1 BattleSession / -2 BattleState / -3 Formation / -4 BattleOrder / -5 BattleEvent / -6 BattleReplay; -7 교전 조건 충족 시에만 세션 생성(C1/C2 금지 가드); 런타임5: -8 fixed tick / -9 idempotency(clientCommandId+sequence) / -10 sequence 부여 / -11 reconnect / -12 AI delegation; -13 4 formation으로 이동·대형전환·사격·사기붕괴·퇴각 검증; -14 replay diff 0 게이트(같은 seed·snapshot·sequence).

**L. C4(§8 C4) 4티켓** — D1-C4-1 출병→전투→replay→BattleResultAdapter→도시/인물/외교 연결; -2 BattleResultAdapter(손실·포로·점령·보급·민심→전략 상태 변경안); -3 보급 fixture(후방 곡창·전방 군량고·농경지+route 1개가 전투 상태에 실제 영향); -4 사령턴 외교가 전투 직접 시작 안 하고 열린 전선에 정책 반영 검증.

**M. C5(§8 C5/§7-5/§10.4/§11-7) 6티켓** — D1-C5-1 usage 측정 / -2 reject reason 측정 / -3 삭제 상태기계 구현 / -4 LOCKED 삭제 금지 가드 / -5 REMOVED 전제 검증(v1 golden·production data·saved queue·adapter 모두 0) / -6 EraPack(오픈 나폴레오닉·엠파이어) 확장 계약.

**N. 게이트·정책(§9,§10,§12) 8티켓** — D1-G1 카탈로그 폐쇄 게이트(registry=4표 합집합, canonical id 정확히 1회); G2 채점표 게이트(항목 하나라도 비면 DRAFT→ACCEPTED 금지); G3 OFFICE_CAPABILITY 우회/LEGACY fallback 시 validation 실패; G4 chief 원군·operation 합류·battle 예비대 서로 다른 id·result 유지; G5 예약 회귀 락(30-slot general_turn/chief nation_turn reserve·push·repeat·pull+due 순서); G6 병합 금지선 가드(비용·RNG·로그 순서 다르면 실행기 병합 금지); G7 재배치 가드(che_증여/헌납/물자조달 링 이동 금지, scope=STRATEGIC 표시만); G8 완료기준 회귀(v1 backend/web gate+PHP golden 불변).

**문서1 non-goals**: 전략 명령용 별도 예약 링 신설 금지; 전술이 개인·사령 링 소비 금지; che_거병/건국/선양/해산 국가 명령 재분류 금지; che_증여/헌납/물자조달 v1 링 이동 금지; 패리티 로그·RNG·부수효과 다른 실행 통합 금지; v1 parser·golden·adapter 선삭제·미사용 추정 삭제 금지; 원군배정·작전합류·전장투입 병합 금지, batch 판정·로그 단일화 금지; v1 backend/web gate·PHP golden 불변.

---

# 문서 2 — 2026-07-13-v2-troop-building-content-catalog.md
목적: 삼국지 CLASSIC 팩의 병력 편제·전통 + 도시 시설·기반망·자원거점 목표 수량·데이터 계약. 병종=Formation 조합(RecruitmentSource+CommandAttachment+MobilityProfile+WeaponLoadout+ProtectionProfile+TrainingAndDoctrine+SupplyProfile+claimIds[]), 정착지 7타입 분리, FacilityCapabilityResolver, CatalogBudgetSlot(BUDGET_ONLY)→ContentEntry(NAMED→CLAIMED→FIXTURE_GREEN→ACTIVE) 생명주기(ACTIVE만 완료 계수).
비범위(명시): 최종 전투 수치, v1 병종 상성·건축물 패리티 변경.
롤아웃: **C0 데이터 계약 → C1 3개 정착지 기술증명 → C2 4개 formation 기술증명 → C2.1 4/3 proof claim map → C3 첫공개 36/24 roster → C4 정체성·황실 확장 → C5 전체 120/72/18/24**.
카탈로그 총수·배치: 병종 120(core 72 실명+확장 48 예산; core=공통18/실명22/지역보조12/공성수군군수10/연의게임참고10); 시설 core48·전체72; 기반망 core10·전체18; 자원거점 core12·전체24; 물리발달 5단계(건물수 미포함); 정착지 kit 24; 지형·계절 profile 32. 확장 48은 **개별 명명 금지 → 최소 단위=family 6개**. **총 티켓 약 212개.**

**A. C0 데이터 계약(§12 C0) 스키마 20티켓** — 병종: D2-C0-1 FormationTemplate / -2 RecruitmentSource / -3 EquipmentProfile(Weapon+Protection) / -4 EvidenceRef / -5 HistoricalClaim(출처 등급 4종) / -6 ContentEntry / -7 CatalogBudget / -8 CatalogBudgetSlot. 정착지: -9 SettlementStatus / -10 Facility / -11 FacilityState / -12 InfrastructureNetwork / -13 ResourceSite / -14 ResourceNode / -15 OfficeFacilityAssignment. 리졸버·검증: -16 FacilityCapabilityResolver(→ALLOW/DENY) / -17 budget-slot/entry lifecycle validator(중복소비·dangling·건너뛰기·역행 거부, ACTIVE만 loader) / -19 claim→evidence 참조 무결성 / -20 content profile validation+provenance badge. **D2-C0-18 실패 fixture 4티켓**: 동일 slot 이중소비 / slot만 소비된 부분생성 / slot-side dangling / entry-side dangling(모두 validation 실패).

**B. 정본 분리·모델(§4,§5) 4티켓** — D2-M1 7타입 별도 타입(한 Building 테이블 금지); M2 SeatAssignment=현치/군치/수도 유일 정본, 중복 저장 금지, UI 배지=SeatAssignment.role 파생; M3 물리 발달 5단계(촌락·리/장시·진/성곽/지역 도시/대도시권, 건물수 미포함, 인구 자동승격 금지); M4 FacilityCapabilityResolver presence-grant 금지(조건 결핍 시 능력 닫거나 capacity 축소).

**C. C1 3개 정착지 기술증명(§12 C1+C2.1) 7티켓** — D2-C1-1 후방 곡창 Facility(facility.rear_granary) / -2 그 ResourceNode; -3 전방 군량고 Facility(facility.forward_granary) / -4 그 ResourceNode; -5 농경지 ResourceSite(resource.grain_field); -6 route 1개에서 재고·예약·수송·소비 검증 fixture(시설·재고 별도 객체); -7 3개 CLAIMED 바인딩 게이트(county-granary-storage/forward-supply-depot/tuntian-production-site+EvidenceRef).

**D. C2 4개 formation 기술증명(§12 C2+C2.1) 6티켓 [T-ENTRY]** — D2-C2-1 징발 창병(formation.conscript_spear) / -2 노수대(formation.crossbow) / -3 경기병대(formation.light_cavalry) / -4 수송호위대(formation.transport_escort); -5 1회 이동·교전·재보급 인원·장비·군량 보존+replay fixture; D2-C2.1-GATE 7개 entry claimIds[]+EvidenceRef 무결(하나라도 끊기면 C2 미통과).

**E. 검증 core 병종 72개(§3.1~3.5) 72티켓 [T-ENTRY]** — 공통완료: 각 항목 모집원·지휘·이동·무장·방호·보급·출처 등급 완비, 실명 병종 시기·소유자·지역·사건 제약. loadout/수치 추가 분해 필요.
· §3.1 공통 18(#1-18): 향리 경비대, 징발 창병✱, 징발 도검병, 방패 도검병, 방패 창병, 극병, 장창병, 경갑 돌격보병, 중갑 보병, 궁수대, 노수대✱, 투사·산병대, 기마궁사대, 경기병대✱, 충격기병대, 중장기병대, 공병대, 수송호위대✱. (✱=C2 선구현, 잔여 14.)
· §3.2 실명 22(#19-40): 둔기교위 휘하 기병대, 월기교위 휘하 기병대, 보병교위 휘하 보병대, 장수교위 휘하 호기대, 사성교위 휘하 사수대(19-23은 OfficeDefinition/CommandAttachment 선행 필요), 호분, 우림, 서원군, 무기교위 변경군, 오환돌기, 백마의종, 청주병, 호표기, 선등, 대극사, 해번병, 감사대, 요장·장하병, 무난독 소속, 단양병, 함진영, 백이·백모병.
· §3.3 지역·보조군 12(#41-52): 강호·제융 보조군, 흉노 보조 기병, 선비 보조 기병, 산월 병원, 남중 부족병, 파인·판순 계열, 양주 군마 전통, 유주 변경 기마, 형주 수륙병, 강동 수군, 익주 산악 주둔병, 서역 변경 둔전병.
· §3.4 공성·수군·군수 10(#53-62): 벽력거·발석거 운용대, 충차 운용대, 누차·공성탑 운용대, 굴착 공병대, 교량·부교 공병대, 둔전 수비대, 군량 수송대, 수레 호송대, 하천 수송선단, 전투 수군 선단.
· §3.5 연의·게임 참고 10(#63-72): 등갑병, 전상대(game.elephant-panic-balance claim 분리), 맹수몰이대, 남중 화염전사, 독천 협곡병, 목우유마 수송대(history.wooden-ox-transport-reconstruction claim, 초자연 성능 금지), 연노 운용대, 비웅군(역사 roster 금지), 금범 유격선단, 무당비군. + D2-E-CHRON 가드(CHRONICLE에서 63~72 자동 비활성/일반 capability 치환).

**F. 확장 병종 48 예산(§3.6) family 6티켓** — 공통완료: CatalogBudgetSlot(BUDGET_ONLY)×N 생성만, entry는 7요소(모집원+지휘 소속+이동+장비+훈련+보급+시기/지역 claim) 충족 시 NAMED 승격→개별 entry 추가 분해 필요. D2-F1 북방(흉노·오환·선비) 10 / F2 강·저·서역 10 / F3 부여·고구려·옥저·읍루·예·삼한 10 / F4 왜 국읍·해상 6 / F5 산월·형남·남중·교주 8 / F6 수군·공성·군수 보강 4.

**G. 검증 core 시설 48(§6) 48티켓 [T-FAC]** —
· §6.1 행정·조정 10: 현 관아, 군 태수부, 주 자사·주목부, 상서·조정 문서부, 문서·인장소, 호적·회계소, 재판소, 감옥, 세무소, 객관·사절관.
· §6.2 농업·창고·시장 8: 현창·공창, 군창·군량고, 구휼 창고, 둔전 치소, 관영 시장, 사영 공방 지구, 국영 공방, 화폐 주조소.
· §6.3 군사·산업 10: 병기고, 무기 제작소, 갑옷 제작소, 수레·수송 공방, 공성 공방, 조선소, 연병장, 모집·징발소, 마구간·역마소, 의관·약초소.
· §6.4 방어·요새 8: 성벽, 성문·문루, 해자·수방, 독립 요새, 관문, 봉수대, 하천 책·쇄강, 민간 대피소.
· §6.5 문화·성향·지역 12: 학교, 사당·향리 제사소, 태묘·사직(수도 전용), 태평도 집회·치병소, 오두미도 제사주 치소, 의사·의미육, 불교 역경·교단소(시기 제한), 도적 산채, 장물 시장, 묵가 공병당, 율력 관측소, 외교 문서관. (40~48은 성향 전용 아님, institution seat=전환/병존.)

**H. 검증 core 기반망 10(§7) 10티켓** — 공통완료: 구간별 capacity·손상·통제·계절·유지 관직(단일 도시 슬롯 아님). 관개·수로·제방망, 도로·교량망, 역참·전령망, 군창·전방 군량고·수송로 보급망, 봉수·초소 경보망, 나루·부두·하천 수송망, 배수·방화·우물 도시 안전망, 태평도 方 연락망, 오두미도 교구·의사망, 도적 산길·산채·장물 연락망.

**I. 검증 core 자원거점 12(§8) 12티켓** — 공통완료: 점유·협약·노동·수송·환경 조건으로 생산량·접근성 변동(무에서 건설 아님). 북방 조·기장·밀 농경지, 남방 논·습지 농경지, 목장·군마 산지, 산림·죽재 거점, 철광·제철 거점, 동광·주조 거점, 염정·염장, 어장·호수·하천 어업, 뽕밭·양잠 거점, 약초·의약 산지, 석재·점토·와요 거점, 옻·목칠·특수 공예 산지.

**J. C3 첫공개 36/24(§12 C3) 2게이트** — D2-C3-1 formation 36개 단계공개 게이트(공통 18+선별 역사·지역·연의, provenance·모집·수송 fixture 있는 항목만); -2 시설·기반망·자원거점 합계 24개 단계공개 게이트.

**K. C4 정체성·황실(§12 C4+§10) 10티켓** — D2-C4-1 태평도 방(타국 도시 network+발각·탄압) / -2 도적 산채·산길·장물(도시 없이 존속+귀순 후 관문·역참·주둔군 전환) / -3 오두미도 제사주 치소·의사망(한식 관아 대체/병존) / -4 유가 학교·추천(교관·학생·추천 관직·천거 필요, 자동 강화 금지) / -5 상서·인장·역참·조서 workflow+중앙/지방 관직 / -6 시설 전환·개혁 확산·점령 후 제도 병존 / -7 법가 재판소·호적소·감찰 집행 / -8 병가 연병장·병기고·군량고·전선 사령 assignment / -9 묵가 공병당·봉수망·대피소 상호 방위 / -10 황실 조서 발행 전제(태묘·사직·상서·관인·역참·궁정 경비 연결).

**L. 시설 분기(§9) 8티켓** — 공통완료: 상위 단계=더 좋은 같은 건물 아님, 전환 시 자산 손실·반발. D2-BR-1 현창(세입/구휼/군량 집산) / -2 관아(호구·감찰/재판·치안/문서·인사) / -3 국영 공방(농기구/무기/수레·공성) / -4 연병장(주둔 수비/야전 대형/기병·궁노) / -5 조선소(수송선/전투선/화공) / -6 학교(경학·천거/율령·문서/군정 참모) / -7 도적 산채(은닉·피난/통행세/대규모 집결) / -8 시설 자산(장인·속관·조직망·재고·문서 이력)+건설·철거 반복 순간보너스 차단.

**M. C5 전체 카탈로그(§12 C5/§8.1) 6티켓** — D2-C5-1 추가 시설 24 예산(변경 관방·역참·항만·목축·서역 교역·한반도 국읍·왜 해상; 2000 지도 거점 구별 기능 있을 때만, 개별 entry 추가 분해 필요) / -2 추가 기반망 8 예산(계절 corridor·해안 항로·도서 항로·말 교역·철 교역·변경 책봉·인질·난민 이동·장거리 상단; SeasonalRange는 기반망/PhysicalPlace 미집계) / -3 추가 자원 유형 12(simulation 바꿀 때만 분리, 추가 분해 필요) / -4 정착지 kit 24(PhysicalPlace 정본 소비, 추가 분해 필요) / -5 지형·계절 profile 32(TerrainRegion 정본 소비, renderer asset 저장 금지, 추가 분해 필요) / -6 C5 Exit 게이트(ACTIVE 120/72/18/24+kit 24+profile 32 정확 충족, BUDGET_ONLY/NAMED/CLAIMED/FIXTURE_GREEN 미포함, 근거 없으면 release gate 실패).

**N. 수용 조건 게이트(§13) 1티켓(9체크)** — D2-ACC: 120 항목마다 모집원·지휘·이동·무장·방호·보급·출처; lifecycle 경계 건너뛰기 불가·ACTIVE만 계수; 실명 병종 전국 모집 금지; CHRONICLE에서 연의·게임 병종 역사 활성화 금지; 시설·기반망·자원거점·정착지 단계·정책 서로 다른 타입; 건물이 퍼센트 버프 아닌 기능 개방; 도시 없는 도적 연맹·타국 태평도 방 표현; 장비·공성기·선박 생산·재고·수송 없이 생성 금지; 같은 content data로 AI·플레이어 동일 조건 평가.

**문서2 non-goals**: 최종 전투 수치·v1 상성·건축물 패리티 변경(비범위); 경장/중장/정예 별도 복제·10단계 선형 업그레이드 금지; 전국 공용 투창병·강호/산월/남중 단일 병종 평면화·단일 남만 종족병 금지; 관직=병종 해석 금지; 공성 장비 즉시 병종화 금지; CHRONICLE 63~72 자동 비활성; 확장 48 선명명·placeholder entry 계수 금지; 가져오지 않을 것=10단계 도시·오행 하드락·정확한 수치·창작 병종 역사화·전역 퍼센트 버프·랜덤 고유 아이템 생성; SeasonalRange 기반망/PhysicalPlace 계수 금지·지역명 복제 자원 금지; 이름만 다른 병종·건물 금지.

---
대상 파일(절대경로): /Users/apple/Desktop/개인프로젝트/opensamguk/docs/superpowers/specs/2026-07-12-v2-command-catalog-and-rollout.md, /Users/apple/Desktop/개인프로젝트/opensamguk/docs/superpowers/specs/2026-07-13-v2-troop-building-content-catalog.md
합계: 문서1 약 207티켓 + 문서2 약 212티켓. 스택 의존성 C0→C1→C2→C3→C4→C5 순.