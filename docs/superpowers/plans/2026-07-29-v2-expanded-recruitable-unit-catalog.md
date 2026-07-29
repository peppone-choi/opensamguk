# OpenSamguk v2 전체 모집 병종 카탈로그

- Date: 2026-07-29
- Status: **PROPOSED**
- Scope: 80개 named `FormationTemplate`을 플레이어가 실제로 모집·개편하는 `RecruitableVariant` 105개로 펼친다. 수치, DB schema, 코드, 에셋 제작은 구현하지 않는다.
- Parent proposal: [v2 병종 카탈로그 개정 제안](2026-07-29-v2-unit-roster-revision-proposal.md)
- Base catalog: [v2 병종·건축물 콘텐츠 카탈로그](../specs/2026-07-13-v2-troop-building-content-catalog.md)

## 1. 결론

최종 제안은 **상위 전통 80개, 실제 모집 병종 105개**다. 주변 세계 확장을 위한 **48개 `BUDGET_ONLY` `CatalogBudgetSlot`**은 이 수에 포함하지 않는다. budget slot은 아직 부모가 아니며, `NAMED` 승격 때 처음 이름·semantic ID·`FormationTemplate`을 얻고 claim·fixture·에셋을 갖춘 뒤에만 `ACTIVE`가 된다.

| 구분 | 부모 전통 | 실제 모집 병종 |
|---|---:|---:|
| 공통 편제, #1–18 | 18 | 24 |
| 실명 부대·지휘기관, #19–40 | 22 | 26 |
| 지역·보조군, #41–52 | 12 | 15 |
| 공성·수군·군수, #53–62 | 10 | 16 |
| `CLASSIC` 연의·게임 참고, #63–72 | 10 | 12 |
| 봉기·종교·군벌 전통, #73–80 | 8 | 12 |
| **named core 합계** | **80** | **105** |
| 주변 세계 `BUDGET_ONLY` `CatalogBudgetSlot` | **48** | **0** |

105개가 한 도시에서 동시에 보인다는 뜻은 아니다. 모집원, 시기, 지역, 지휘망, 시설, 외교, 장비 재고, content profile을 해소한 뒤 대표 도시의 모집 화면에는 보통 6–12개가 나타난다.

## 2. 카탈로그 계층

```text
FormationTemplate
  역사적 정체성·모집 전통·profile·상한·계보
    -> VariantRecipe
       이동·무장·방호·교리·보급·승조원·명령의 중복 제거 recipe
         -> RecruitableVariant
            플레이어와 AI가 고르는 실제 모집·개편 카드
              -> FormationInstance
                 전투 runtime의 FormationState
                 formationInstanceId, formationTemplateId
                 recruitableVariantId, catalogRevision, cadreSlotId?
                 병력·장비 수량/품질/상태·숙련·경험·사기·피로
```

- 부모 ID는 `formation.<semantic_parent>`, 자식 ID는 `variant.<semantic_parent>.<persistent_role_or_loadout>` 형식의 소문자 ASCII를 쓴다.
- 각 variant ID의 가운데 `semantic_parent`는 canonical parent ID와 정확히 같아야 하며 catalog compiler가 두 ID를 모두 저장한다. 예를 들어 #11은 `formation.crossbow`와 `variant.crossbow.line_crossbow`다. runtime에서 문자열을 잘라 부모를 추론하지 않는다.
- v1 숫자 ID, 소유 세력, 인물, 연도, `elite`, `veteran`, 품질 등급을 semantic ID에 넣지 않는다.
- `RecruitableVariant` 행은 능력·명령, 이동·footprint·지형 행동, 주무기 행동, 승조원·보급 구조, 플랫폼·임무, 독립된 모집 계보 중 하나가 지속적으로 달라질 때만 만든다.
- `LEVY | REGULAR | CADRE`, `GREEN | TRAINED | VETERAN`, 장비 품질·상태·수량, 탄약 수, 병력, 사기, 피로, 경험, 가격·시간 배율, 진영색, 도시·지휘관, cosmetic만 다른 경우 새 병종을 만들지 않는다.
- 갑옷이 실제 이동·지형·명령·보급·실루엣을 함께 바꾸지 않는다면 `경갑/중갑/정예`라는 수식어만으로 행을 나누지 않는다.
- 장비 열의 `·`는 동시에 존재하는 고정 혼성 recipe를 뜻한다. 모집 때 다른 무기 행동으로 바꾸는 선택지가 아니다. recipe가 요구한 장비가 모자라면 variant를 바꾸지 않고 `degraded`가 된다.

### 2.1 canonical parent ID

| # | 부모 전통 | canonical parent ID |
|---:|---|---|
| 1 | 향리 경비대 | `formation.local_guard` |
| 2 | 징발 창병 | `formation.conscript_spear` |
| 3 | 징발 도검병 | `formation.conscript_sword` |
| 4 | 방패 도검병 | `formation.shield_sword` |
| 5 | 방패 창병 | `formation.shield_spear` |
| 6 | 극병 | `formation.halberd` |
| 7 | 장창병 | `formation.long_spear` |
| 8 | 경갑 돌격보병 | `formation.light_assault_infantry` |
| 9 | 중갑 보병 | `formation.heavy_infantry` |
| 10 | 궁수대 | `formation.archers` |
| 11 | 노수대 | `formation.crossbow` |
| 12 | 투사·산병대 | `formation.skirmishers` |
| 13 | 기마궁사대 | `formation.horse_archers` |
| 14 | 경기병대 | `formation.light_cavalry` |
| 15 | 충격기병대 | `formation.shock_cavalry` |
| 16 | 중장기병대 | `formation.heavy_cavalry` |
| 17 | 공병대 | `formation.engineers` |
| 18 | 수송호위대 | `formation.transport_escort` |
| 19 | 둔기교위 휘하 기병대 | `formation.tunqi_command` |
| 20 | 월기교위 휘하 기병대 | `formation.yueqi_command` |
| 21 | 보병교위 휘하 보병대 | `formation.infantry_commandant` |
| 22 | 장수교위 휘하 호기대 | `formation.changshui_command` |
| 23 | 사성교위 휘하 사수대 | `formation.shesheng_command` |
| 24 | 호분 | `formation.huben` |
| 25 | 우림 | `formation.yulin` |
| 26 | 서원군 | `formation.xiyuan_army` |
| 27 | 무기교위 변경군 | `formation.wuji_frontier` |
| 28 | 오환돌기 | `formation.wuhuan_raiders` |
| 29 | 백마의종 | `formation.white_horse_retinue` |
| 30 | 청주병 | `formation.qingzhou` |
| 31 | 호표기 | `formation.tiger_leopard_cavalry` |
| 32 | 선등 | `formation.xiandeng` |
| 33 | 대극사 | `formation.great_halberd_guard` |
| 34 | 해번 양부 | `formation.jiefan_liangbu` |
| 35 | 결사대 | `formation.dare_to_die` |
| 36 | 차하호사 | `formation.chariot_side_tigers` |
| 37 | 무난독 휘하 병력 | `formation.wunan_command` |
| 38 | 단양병 | `formation.danyang` |
| 39 | 함진영 | `formation.xianzhenying` |
| 40 | 백이병 | `formation.white_tuft_guard` |
| 41 | 강호·제융 보조군 | `formation.qiang_hu_auxiliary` |
| 42 | 흉노 보조 기병 | `formation.xiongnu_auxiliary` |
| 43 | 선비 보조 기병 | `formation.xianbei_auxiliary` |
| 44 | 산월 병원 | `formation.shanyue_recruits` |
| 45 | 남중 부족병 | `formation.nanzhong_tribal` |
| 46 | 판순병 | `formation.banshun` |
| 47 | 양주기병 | `formation.liangzhou_cavalry` |
| 48 | 유주 변경 기마 | `formation.youzhou_frontier_cavalry` |
| 49 | 형주 수륙병 | `formation.jingzhou_amphibious` |
| 50 | 강동 수군 | `formation.jiangdong_navy` |
| 51 | 익주 산악 주둔병 | `formation.yizhou_mountain_garrison` |
| 52 | 서역 변경 둔전병 | `formation.western_tuntian` |
| 53 | 벽력거·발석거 운용대 | `formation.stone_thrower_corps` |
| 54 | 충차 운용대 | `formation.ram_corps` |
| 55 | 누차·공성탑 운용대 | `formation.siege_tower_corps` |
| 56 | 굴착 공병대 | `formation.mining_engineers` |
| 57 | 교량·부교 공병대 | `formation.bridge_engineers` |
| 58 | 둔전 수비대 | `formation.tuntian_garrison` |
| 59 | 군량 수송대 | `formation.grain_transport` |
| 60 | 수레 호송대 | `formation.wagon_convoy` |
| 61 | 하천 수송선단 | `formation.river_transport` |
| 62 | 전투 수군 선단 | `formation.battle_fleet` |
| 63 | 등갑병 | `formation.rattan_armour` |
| 64 | 전상대 | `formation.war_elephant` |
| 65 | 맹수몰이대 | `formation.beast_handlers` |
| 66 | 남중 화염전사 | `formation.nanzhong_fire_warriors` |
| 67 | 독천 협곡병 | `formation.poison_spring_ambushers` |
| 68 | 목우유마 수송대 | `formation.wooden_ox_transport` |
| 69 | 원융노 운용대 | `formation.repeating_crossbow_corps` |
| 70 | 비웅군 | `formation.flying_bear_guard` |
| 71 | 금범 유격선단 | `formation.jinfan_raiders` |
| 72 | 무당비군 | `formation.wudang_feijun` |
| 73 | 호사 | `formation.tiger_guard` |
| 74 | 황중의종 | `formation.huangzhong_yicong` |
| 75 | 동주병 | `formation.dongzhou_troops` |
| 76 | 진호병 | `formation.qinhu_troops` |
| 77 | 황건 동원군 | `formation.yellow_turban_mobilization` |
| 78 | 흑산 산악군 | `formation.black_mountain` |
| 79 | 백파 유격군 | `formation.white_wave` |
| 80 | 오두미도 조직대 | `formation.five_pecks_organization` |

## 3. 전체 105개 모집 병종

표의 `gate`는 이름의 존재와 정확한 무장·편제의 근거를 분리한다. `PROPOSED`나 `claim 필요`인 행은 claim, 모집 fixture, 전투 fixture, visual recipe를 통과하기 전까지 `ACTIVE`가 아니다.

### 3.1 공통 편제 — 24개

| # | 부모 전통 | 실제 모집 병종 | stable variant ID | 이동·플랫폼 | 주 장비 | 지속 전술 정체성 | gate |
|---:|---|---|---|---|---|---|---|
| 1 | 향리 경비대 | 향리 도검경비대 | `variant.local_guard.sword_guard` | 보행·주둔 | 도검 | 근접 치안·거점 방어 | 지역 향리 모집원 |
| 1 | 향리 경비대 | 향리 창경비대 | `variant.local_guard.spear_guard` | 보행·주둔 | 창 | 관문 저지·대기병 보조 | 지역 향리 모집원 |
| 1 | 향리 경비대 | 향리 궁경비대 | `variant.local_guard.bow_guard` | 보행·주둔 | 활 | 성책·마을 원거리 방어 | 활 재고 |
| 2 | 징발 창병 | 징발 창병 | `variant.conscript_spear.line_spear` | 보행 | 창 | 저비용 전열·돌격 저지 | 징발 인구 |
| 3 | 징발 도검병 | 징발 도검병 | `variant.conscript_sword.line_sword` | 보행 | 도검 | 저비용 근접 전열 | 징발 인구 |
| 4 | 방패 도검병 | 방패 도검병 | `variant.shield_sword.shield_sword` | 보행 | 도검·방패 | 사격 방호·근접 유지 | 방패 재고 |
| 5 | 방패 창병 | 방패 창병 | `variant.shield_spear.shield_spear` | 보행 | 창·방패 | 전면 저지·방패 엄호 | 창·방패 재고 |
| 6 | 극병 | 극병 | `variant.halberd.line_halberd` | 보행 | 극 | 장병기 전열·기마 견제 | 극 재고 |
| 7 | 장창병 | 장창병 | `variant.long_spear.pike_line` | 보행·큰 footprint | 장창 | 밀집 창벽·정면 저지 | 장창·훈련 시설 |
| 8 | 경갑 돌격보병 | 경갑 도끼돌격병 | `variant.light_assault_infantry.axe_assault` | 보행·기동 | 도끼 | 방패·목책 파괴와 짧은 돌입 | 도끼 행동 fixture |
| 8 | 경갑 돌격보병 | 경갑 장병돌격병 | `variant.light_assault_infantry.polearm_assault` | 보행·기동 | 장병기 | 긴 reach를 이용한 돌입 | 장병기 행동 fixture |
| 9 | 중갑 보병 | 중갑 도검방패병 | `variant.heavy_infantry.sword_shield` | 보행·저기동 | 도검·대형 방패 | 느린 전열 유지·거점 돌파 | 중방호가 이동·보급도 변경 |
| 10 | 궁수대 | 궁수대 | `variant.archers.line_archer` | 보행·대열 | 활 | 준비된 곡사·지속 사격 | 활·화살 재고 |
| 11 | 노수대 | 노수대 | `variant.crossbow.line_crossbow` | 보행·대열 | 노 | 관통 일제사격·재장전 관리 | 노·촉 재고 |
| 12 | 투사·산병대 | 투창 산병대 | `variant.skirmishers.javelin` | 보행·산개 | 투창 | 근거리 견제·후퇴 사격 | 산개 명령 fixture |
| 12 | 투사·산병대 | 궁산병대 | `variant.skirmishers.bow` | 보행·산개 | 단궁 | 기동 곡사·험지 견제 | #10과 대형·명령 차이 필요 |
| 12 | 투사·산병대 | 노산병대 | `variant.skirmishers.crossbow` | 보행·산개 | 경노 | 분산 직사·엄폐 활용 | #11과 대형·명령 차이 필요 |
| 13 | 기마궁사대 | 기마궁사대 | `variant.horse_archers.mounted_bow` | 기마 | 활 | 이동 사격·유인·추격 | 말·활 재고 |
| 14 | 경기병대 | 경기 창기병대 | `variant.light_cavalry.spear` | 경장 기마 | 창 | 정찰·측면 돌입·추격 | 말·창 재고 |
| 14 | 경기병대 | 경기 도기병대 | `variant.light_cavalry.sabre` | 경장 기마 | 도 | 산개 기병전·추격 근접 | 말·도검 재고 |
| 15 | 충격기병대 | 충격 창기병대 | `variant.shock_cavalry.lance` | 기마·돌격 대형 | 기병창 | 단발 충격·측후면 돌파 | 돌격 훈련·말 재고 |
| 16 | 중장기병대 | 중장 창기병대 | `variant.heavy_cavalry.armored_lance` | 중장 기마 | 기병창·기수 갑주 | 지속 압박·충격 생존 | 갑주가 이동·보급도 변경 |
| 17 | 공병대 | 야전 축성공병대 | `variant.engineers.fieldworks` | 보행·공구 수레 | 삽·도끼·말뚝 | 진지·장애물·야전 수리 | #56 굴착, #57 교량과 중복 금지 |
| 18 | 수송호위대 | 수송 호위대 | `variant.transport_escort.convoy_screen` | 호송 대상 추종 | 창·방패·수리 공구 | convoy screen·이탈 억제 | 수레/짐말은 대상 profile |

### 3.2 실명 부대·지휘기관 — 26개

| # | 부모 전통 | 실제 모집 병종 | stable variant ID | 이동·플랫폼 | 주 장비 | 지속 전술 정체성 | gate |
|---:|---|---|---|---|---|---|---|
| 19 | 둔기교위 휘하 기병대 | 둔기교위 휘하 기병대 | `variant.tunqi_command.cavalry` | 기마 | 기병창·도 | 관서·직속 지휘 기병 | 관직 identity `PRIMARY_ATTESTED`; loadout `SCHOLARLY_RECONSTRUCTION` |
| 20 | 월기교위 휘하 기병대 | 월기교위 휘하 기병대 | `variant.yueqi_command.cavalry` | 기마 | 기병창·궁 | 수도 직속 기병 | 관직 identity `PRIMARY_ATTESTED`; loadout `SCHOLARLY_RECONSTRUCTION` |
| 21 | 보병교위 휘하 보병대 | 보병교위 휘하 보병대 | `variant.infantry_commandant.infantry` | 보행 | 창·방패 | 직속 보병 지휘대 | 관직 identity `PRIMARY_ATTESTED`; loadout `SCHOLARLY_RECONSTRUCTION` |
| 22 | 장수교위 휘하 호기대 | 장수교위 휘하 호기대 | `variant.changshui_command.hu_cavalry` | 기마 | 궁·창 | 변경 모집 기병 지휘대 | 관직 identity `PRIMARY_ATTESTED`; loadout `SCHOLARLY_RECONSTRUCTION` |
| 23 | 사성교위 휘하 사수대 | 사성교위 휘하 사수대 | `variant.shesheng_command.marksmen` | 보행 | 활·노 고정 혼성 | 직속 사수 지휘대 | 관직 identity `PRIMARY_ATTESTED`; 혼성 비율 fixture 필요 |
| 24 | 호분 | 호분 | `variant.huben.palace_guard` | 보행 | 도검·방패 | 궁궐·지휘부 친위 | identity `PRIMARY_ATTESTED`; loadout `SCHOLARLY_RECONSTRUCTION` |
| 25 | 우림 | 우림 | `variant.yulin.imperial_escort` | 기마 | 궁·창 고정 혼성 | 황실 호위·의장 경계 | identity `PRIMARY_ATTESTED`; 기마 loadout claim 필요 |
| 26 | 서원군 | 서원군 직속대 | `variant.xiyuan_army.command_cadre` | 보행·혼성 footprint | 창·노 고정 혼성 | 서원 지휘망의 직속 cadre | identity `PRIMARY_ATTESTED`; 시기·혼성 recipe fixture |
| 27 | 무기교위 변경군 | 무기교위 직속 변경대 | `variant.wuji_frontier.command_cadre` | 보행·짐말 동반 | 창·노 고정 혼성 | 서역 직속 변경 지휘대 | identity `PRIMARY_ATTESTED`; #52 둔전로 경비와 구분 |
| 28 | 오환돌기 | 오환돌기 | `variant.wuhuan_raiders.mounted_auxiliary` | 기마 | 궁·창 고정 혼성 | 빠른 변경 보조기병 | identity `PRIMARY_ATTESTED`; 외교·모집 계약 |
| 29 | 백마의종 | 백마의종 | `variant.white_horse_retinue.mounted_retinue` | 기마 | 궁·창 고정 혼성 | 백마 표지의 제한 기마 cadre | identity `PRIMARY_ATTESTED`; 공손찬 계보·말 |
| 30 | 청주병 | 청주 창병 | `variant.qingzhou.spear` | 보행 | 창 | 지역·조직 결속의 전열 | 청주 모집원 |
| 30 | 청주병 | 청주 극병 | `variant.qingzhou.halberd` | 보행 | 극 | 장병기 전열·기마 견제 | 청주 모집원·극 |
| 30 | 청주병 | 청주 도검돌격대 | `variant.qingzhou.sword_assault` | 보행·돌입 | 도검 | 조직 결속을 이용한 돌입 | 별도 돌격 fixture |
| 31 | 호표기 | 호표기 | `variant.tiger_leopard_cavalry.shock` | 기마·돌격 대형 | 기병창·도 | 제한 충격기병 cadre | identity `PRIMARY_ATTESTED`; loadout `SCHOLARLY_RECONSTRUCTION` |
| 32 | 선등 | 국의 선등대 | `variant.xiandeng.crossbow_vanguard` | 보행 | 강노·방패 | 노병 선봉·접근 제압 | 국의 지휘 계보·상한 |
| 33 | 대극사 | 대극사 | `variant.great_halberd_guard.command_guard` | 보행 | 대극 | 원소 지휘부 근접 호위 | 제한 cadre |
| 34 | 해번 양부 | 해번 양부 | `variant.jiefan_liangbu.command_cadre` | 보행 | 도검·방패 | 오 지휘부 제한 cadre | identity `PRIMARY_ATTESTED`; loadout `BALANCE_ONLY`; `LEFT`/`RIGHT`는 slot |
| 35 | 결사대 | 결사대 | `variant.dare_to_die.mission_assault` | 보행 | 도검·방패 | 일회성 고위험 돌입 임무 | identity `PRIMARY_ATTESTED`; mission loadout `BALANCE_ONLY` |
| 36 | 차하호사 | 차하호사 | `variant.chariot_side_tigers.command_guard` | 보행 | 도검·방패 | 지휘부 근접 호위 | identity `PRIMARY_ATTESTED`; 차량 운용병 해석 금지 |
| 37 | 무난독 휘하 병력 | 무난독 휘하 병력 | `variant.wunan_command.command_cadre` | 보행·혼성 footprint | 창·노 고정 혼성 | 관직에 묶인 직속 cadre | identity `PRIMARY_ATTESTED`; loadout `BALANCE_ONLY` |
| 38 | 단양병 | 단양 창병 | `variant.danyang.spear` | 보행 | 창 | 단양 모집 전통의 전열 | 지역 모집원 |
| 38 | 단양병 | 단양 궁병 | `variant.danyang.bow` | 보행 | 활 | 단양 모집 전통의 사격대 | 지역 모집원·활 |
| 38 | 단양병 | 단양 노병 | `variant.danyang.crossbow` | 보행 | 노 | 단양 모집 전통의 노수대 | 지역 모집원·노 |
| 39 | 함진영 | 함진영 | `variant.xianzhenying.ordered_assault` | 보행·밀집 돌입 | 장병기·도검 고정 혼성 | 제한된 정돈 돌격 cadre | identity `PRIMARY_ATTESTED`; loadout `BALANCE_ONLY`; 중장 창방패 단정 금지 |
| 40 | 백이병 | 백이병 | `variant.white_tuft_guard.command_guard` | 보행 | 창·방패 | 백모 표지의 제한 친병 | identity `SCHOLARLY_RECONSTRUCTION`; 유비 계보·상한 |

### 3.3 지역·보조군 — 15개

| # | 부모 전통 | 실제 모집 병종 | stable variant ID | 이동·플랫폼 | 주 장비 | 지속 전술 정체성 | gate |
|---:|---|---|---|---|---|---|---|
| 41 | 강호·제융 보조군 | 강호·제융 동맹 기마대 | `variant.qiang_hu_auxiliary.allied_mounted` | 기마 | 궁·창 고정 혼성 | 외교 관계 기반 보조기병 | identity·loadout `SCHOLARLY_RECONSTRUCTION`; 관계 계약 |
| 42 | 흉노 보조 기병 | 흉노 협약 기마대 | `variant.xiongnu_auxiliary.treaty_mounted` | 기마 | 궁·창 고정 혼성 | 협약 기반 변경 기병 | identity·loadout `SCHOLARLY_RECONSTRUCTION`; 외교 gate |
| 43 | 선비 보조 기병 | 선비 보조 기마대 | `variant.xianbei_auxiliary.mounted` | 기마 | 궁·창 고정 혼성 | 선비 모집망의 보조기병 | identity·loadout `SCHOLARLY_RECONSTRUCTION`; 외교 gate |
| 44 | 산월 병원 | 산월 산악 산병대 | `variant.shanyue_recruits.hill_skirmisher` | 보행·험지 | 투창·단궁 고정 혼성 | 산악 분산 이동·매복 | identity·loadout `SCHOLARLY_RECONSTRUCTION`; 관계 gate |
| 45 | 남중 부족병 | 남중 투창 산병대 | `variant.nanzhong_tribal.javelin_skirmisher` | 보행·험지 | 투창 | 숲·구릉 견제와 매복 | 부족 관계·지역 모집원 |
| 46 | 판순병 | 판순 방패창병 | `variant.banshun.shield_spear` | 보행·험지 | 창·방패 | 험지 전열·관문 저지 | 판순 모집원 |
| 46 | 판순병 | 판순 백죽노병 | `variant.banshun.white_bamboo_crossbow` | 보행·험지 | 백죽노 | 험지 노수 지원 | 독립 historical claim 전에는 비활성 |
| 47 | 양주기병 | 양주 경기병 | `variant.liangzhou_cavalry.light` | 경장 기마 | 창·도 고정 혼성 | 정찰·추격 | 양주 말·모집원 |
| 47 | 양주기병 | 양주 궁기병 | `variant.liangzhou_cavalry.mounted_archer` | 기마 | 활 | 이동 사격·유인 | 말·활 재고 |
| 47 | 양주기병 | 양주 철기 | `variant.liangzhou_cavalry.armored_shock` | 중장 기마 | 창·갑주 | 충격 돌파 | 갑주가 이동·보급도 변경 |
| 48 | 유주 변경 기마 | 유주 변경 기마척후 | `variant.youzhou_frontier_cavalry.scout` | 경장 기마 | 궁·창 고정 혼성 | 장거리 정찰·변경 순찰 | 유주 말·변경 모집원 |
| 49 | 형주 수륙병 | 형주 수륙 주둔대 | `variant.jingzhou_amphibious.river_land_garrison` | 보행·선박 승하선 | 창·노 고정 혼성 | 수륙 거점 전환·연안 주둔 | 형주 수로 모집원 |
| 50 | 강동 수군 | 강동 선상 육전대 | `variant.jiangdong_navy.shipboard_marines` | 선박 탑승 보병 | 활·노·접현 무기 | 선상 전투 인력 | #62 선박 플랫폼과 구분 |
| 51 | 익주 산악 주둔병 | 익주 관문 주둔대 | `variant.yizhou_mountain_garrison.pass_guard` | 보행·산악 | 창·노 고정 혼성 | 관문·잔도 고정 방어 | 익주 관문 모집원 |
| 52 | 서역 변경 둔전병 | 서역 둔전로 순호대 | `variant.western_tuntian.route_guard` | 보행·짐말 동반 | 창·노 고정 혼성 | 둔전 사이 경로 순찰·호송 | loadout `SCHOLARLY_RECONSTRUCTION`; #58과 구분 |

### 3.4 공성·수군·군수 — 16개

| # | 부모 전통 | 실제 모집 병종 | stable variant ID | 이동·플랫폼 | 주 장비 | 지속 전술 정체성 | gate |
|---:|---|---|---|---|---|---|---|
| 53 | 벽력거·발석거 운용대 | 발석거 운용대 | `variant.stone_thrower_corps.stone_thrower` | 해체/견인 공성 플랫폼 | 발석기·탄석 | 성벽·밀집대형 간접 타격 | 벽력거는 시기·claim alias |
| 54 | 충차 운용대 | 충차 운용대 | `variant.ram_corps.covered_ram` | 인력/견인 충차 | 충목·보호 지붕 | 성문·목책 파괴 | 생산된 충차 inventory |
| 55 | 누차·공성탑 운용대 | 누차 사격대 | `variant.siege_tower_corps.elevated_fire` | 차륜 고가 플랫폼 | 활·노 | 성벽 위 제압 사격 | 누차 inventory·승조원 |
| 55 | 누차·공성탑 운용대 | 공성탑 강습대 | `variant.siege_tower_corps.wall_assault` | 성벽 접촉 탑 | 경사판·사다리 | 별도 보병을 성벽에 전달 | 공성탑 inventory·탑승대 |
| 56 | 굴착 공병대 | 굴착 공병대 | `variant.mining_engineers.sapper` | 보행·지하 작업 | 곡괭이·삽·지보재 | 기초 공격·대응 굴착 | #17 야전 축성과 구분 |
| 57 | 교량·부교 공병대 | 가설교 공병대 | `variant.bridge_engineers.field_bridge` | 보행·공구 수레 | 목재·도끼·밧줄 | 도로·고정교 복구 | 교량 자재 inventory |
| 57 | 교량·부교 공병대 | 부교 공병대 | `variant.bridge_engineers.pontoon_bridge` | 보행·부교 플랫폼 | 배·부재·닻 | 임시 하천 횡단 개설 | 수상 자재·하천 gate |
| 58 | 둔전 수비대 | 둔전 수비대 | `variant.tuntian_garrison.site_guard` | 보행·주둔 | 창·노·야전 장애물 | 생산 거점·창고 방위 | #52 경로 순찰과 구분 |
| 59 | 군량 수송대 | 짐승 군량수송대 | `variant.grain_transport.pack_supply` | 보행·짐승 행렬 | 안장짐·곡물·사료 | 험로 군량·급료 수송 | 짐승·군량 inventory |
| 60 | 수레 호송대 | 수레 호송대 | `variant.wagon_convoy.heavy_convoy` | 도로 의존 수레 | 수리 공구·호위 무기 | 장비·부상자·공성 부품 수송 | 수레 inventory |
| 61 | 하천 수송선단 | 지류 운송선단 | `variant.river_transport.shallow_draft` | 얕은 흘수 선박 | 장대·예인줄·화물 설비 | 지류 접근·저용량 수송 | 얕은 수로·선박 inventory |
| 61 | 하천 수송선단 | 대하 화물선단 | `variant.river_transport.heavy_barge` | 깊은 흘수 바지선 | 화물 설비·닻 | 본류 고용량 수송 | 깊은 수로·선박 inventory |
| 62 | 전투 수군 선단 | 경전선 유격선단 | `variant.battle_fleet.light_patrol` | 경량 전투선 | 활·노·노 | 정찰·차단·추격 | 선박·승조원 inventory |
| 62 | 전투 수군 선단 | 누선 사격선단 | `variant.battle_fleet.tower_ship_battery` | 누선·깊은 흘수 | 활·노·목제 사격탑 | 원거리 수역 통제 | 누선 inventory |
| 62 | 전투 수군 선단 | 접현 강습선단 | `variant.battle_fleet.boarding_assault` | 기동 전투선 | 갈고리·현교 | 접현·별도 육전대 승선 | #50 육전 인력 필요 |
| 62 | 전투 수군 선단 | 화공선단 | `variant.battle_fleet.fire_attack` | 소모성 화공선 | 발화 화물·예인선 | 수역 차단·화공 교란 | 발화물 inventory·풍향 gate |

### 3.5 `CLASSIC` 연의·게임 참고 — 12개

| # | 부모 전통 | 실제 모집 병종 | stable variant ID | 이동·플랫폼 | 주 장비 | 지속 전술 정체성 | gate |
|---:|---|---|---|---|---|---|---|
| 63 | 등갑병 | 등갑 창방패병 | `variant.rattan_armour.shield_spear` | 보행·습지/숲 | 창·방패·등갑 | 험지 저지·명시적 화염 취약 | `CLASSIC`, `ROMANCE_ATTESTED→BALANCE_ONLY` |
| 63 | 등갑병 | 등갑 산병대 | `variant.rattan_armour.rough_skirmisher` | 보행·산개·험지 | 투창·단궁·등갑 고정 혼성 | 험지 기동 견제 | `CLASSIC`, 별도 행동 fixture |
| 64 | 전상대 | 전상 돌격대 | `variant.war_elephant.shock` | 코끼리 플랫폼 | 창·몰이 도구 | 충격·공황 | `CLASSIC`, 코끼리 자원 |
| 64 | 전상대 | 전상 사격대 | `variant.war_elephant.missile` | 코끼리 사격 플랫폼 | 투창·활 고정 혼성 | 고가 이동 사격 | `CLASSIC`, 코끼리·사격대 자원 |
| 65 | 맹수몰이대 | 맹수몰이대 | `variant.beast_handlers.disruption` | 보행·동물 asset | 우리·장대·소음 도구 | 통제 실패 위험이 있는 교란 | `CLASSIC`, 종별 병종 분리 금지 |
| 66 | 남중 화염전사 | 남중 화염투척대 | `variant.nanzhong_fire_warriors.incendiary_skirmisher` | 보행·산개 | 화염 단지·횃불 | 목재·진지 지역 거부 | `CLASSIC`, 마법 금지 |
| 67 | 독천 협곡병 | 독천 협곡매복대 | `variant.poison_spring_ambushers.gorge_ambush` | 보행·산악 | 활·창·함정 | 협곡 매복·경로 차단 | `CLASSIC`, 독은 terrain/event |
| 68 | 목우유마 수송대 | 목우유마 수송대 | `variant.wooden_ox_transport.mountain_supply` | 보행·기계 운반구 | 화물틀·정비 도구 | 좁은 산악로 군수 | `CLASSIC`, 초자연 성능 금지 |
| 69 | 원융노 운용대 | 원융노 운용대 | `variant.repeating_crossbow_corps.sustained_volley` | 보행·준비 사격 | 원융노·방패 | 근·중거리 지속 일제사격 | parent/variant policy `CLASSIC`; 일반 반복노 claim·fixture가 있으면 `CHRONICLE_IF_CLAIMED` |
| 70 | 비웅군 | 비웅 친위대 | `variant.flying_bear_guard.command_guard` | 보행 | 창·극·도 고정 혼성 | 지휘부 호위·역돌격 | `CLASSIC`, 역사 roster 금지 |
| 71 | 금범 유격선단 | 금범 유격선단 | `variant.jinfan_raiders.fast_boarding` | 제한 고속선 | 활·노·갈고리 | 수상 습격·차단·접현 | `CLASSIC`, 제한 cadre |
| 72 | 무당비군 | 무당비군 산악대 | `variant.wudang_feijun.mountain_infantry` | 보행·산악 | 창·노·방패 | 산악 지구력·매복·경로 차단 | `CLASSIC`, 명칭 claim 분리 |

### 3.6 봉기·종교·군벌 전통 — 12개

| # | 부모 전통 | 실제 모집 병종 | stable variant ID | 이동·플랫폼 | 주 장비 | 지속 전술 정체성 | gate |
|---:|---|---|---|---|---|---|---|
| 73 | 호사 | 호사 친위대 | `variant.tiger_guard.command_guard` | 보행 | 도검·방패 | 허저 지휘 계보의 근접 호위 | identity `PRIMARY_ATTESTED`; loadout `BALANCE_ONLY`; 제한 cadre |
| 74 | 황중의종 | 황중의종 기마대 | `variant.huangzhong_yicong.mounted_retinue` | 기마 | 궁·창 고정 혼성 | 변경 의종 기동대 | identity `PRIMARY_ATTESTED`; 기마 recipe `SCHOLARLY_RECONSTRUCTION`; 말 필요 |
| 75 | 동주병 | 동주병 | `variant.dongzhou_troops.migrant_cohort` | 보행 | 창·방패 | 이주민 모집망·정치 결속 | identity `PRIMARY_ATTESTED`; loadout `BALANCE_ONLY` |
| 76 | 진호병 | 진호병 | `variant.qinhu_troops.regional_cadre` | 보행·혼성 footprint | 창·궁 고정 혼성 | 관중 서부 모집 계보 | identity `PRIMARY_ATTESTED`; loadout `BALANCE_ONLY`; 민족 고정 금지 |
| 77 | 황건 동원군 | 황건 도병 | `variant.yellow_turban_mobilization.sword` | 보행 | 도검 | 종교 조직망의 근접 동원 | identity `SCHOLARLY_RECONSTRUCTION`; split `BALANCE_ONLY` |
| 77 | 황건 동원군 | 황건 창병 | `variant.yellow_turban_mobilization.spear` | 보행 | 창 | 종교 조직망의 전열 동원 | identity `SCHOLARLY_RECONSTRUCTION`; split `BALANCE_ONLY` |
| 77 | 황건 동원군 | 황건 궁병 | `variant.yellow_turban_mobilization.bow` | 보행 | 활 | 종교 조직망의 사격 동원 | identity `SCHOLARLY_RECONSTRUCTION`; split `BALANCE_ONLY` |
| 78 | 흑산 산악군 | 흑산 투창산병대 | `variant.black_mountain.javelin_skirmisher` | 보행·산악·산개 | 투창 | 산악 견제·분산 집결 | identity `SCHOLARLY_RECONSTRUCTION`; split `BALANCE_ONLY` |
| 78 | 흑산 산악군 | 흑산 방패 경보병 | `variant.black_mountain.shield_light_infantry` | 보행·산악 | 도검·창·방패 고정 혼성 | 험지 근접 유지 | identity `SCHOLARLY_RECONSTRUCTION`; split `BALANCE_ONLY` |
| 78 | 흑산 산악군 | 흑산 기동 궁병 | `variant.black_mountain.mobile_archer` | 보행·산악·산개 | 활 | 산악 이동 사격 | identity `SCHOLARLY_RECONSTRUCTION`; split `BALANCE_ONLY` |
| 79 | 백파 유격군 | 백파곡 도검유격대 | `variant.white_wave.sword_raider` | 보행·산악 | 도검 | 계곡·하천 통로 습격 | identity `SCHOLARLY_RECONSTRUCTION`; split `BALANCE_ONLY` |
| 80 | 오두미도 조직대 | 오두미도 연락호위대 | `variant.five_pecks_organization.courier_guard` | 보행·산악로 | 창·도 고정 혼성 | 연락·의창·조직망 호위 | identity `SCHOLARLY_RECONSTRUCTION`; role `BALANCE_ONLY`; 마법 금지 |

## 4. 모집·개편·상한

`RecruitmentOffer`는 카탈로그 정적 행이 아니라 서버가 현재 world snapshot으로 해소한 quote다.

```text
RecruitmentOffer
  worldId, cityId, catalogRevision
  formationTemplateId, recruitableVariantId
  sourceIds[], selectedSourceId?
  AVAILABLE | BLOCKED
  reasonCodes[], capUsage
  cadreSlotId?, lineageReservationId?
  inventoryCost, moneyCost, duration
  provenanceBadge, visualRecipeId
```

- lifecycle 비활성, profile 금지, 미발견, 문맥 밖 병종은 숨긴다.
- 현재 관련되지만 재고·시설·모집원·지휘망·상한·queue가 부족한 행은 원인과 해소법을 붙여 비활성 표시한다.
- UI와 AI는 같은 resolver를 사용하고, intake와 engine은 같은 snapshot에서 allow/deny와 reason code가 일치해야 한다.
- 모집 화면은 variant마다 한 행을 만들며 같은 병종의 여러 source는 행 안에서 고른다. 정당한 13번째 행을 임의로 자르지 않고 scrolling 또는 pagination으로 모두 접근하게 한다.
- 전투 중 무장형 전환은 없다. 개편은 명시적 방향의 `RefitEdge`로만 수행하며 시설, 장비 입출고, 시간, 숙련 보존 정책을 가진다.
- 같은 부모 안의 개편이 기본이다. 다른 부모로 옮기는 것은 별도 재편·승계 operation이다.
- 상한과 살아 있는 계보는 부모·cadre slot에 귀속된다. 형제 variant로 개편해도 상한을 회피하거나 계보를 복제하지 못한다.
- 장비가 부족하거나 파손돼도 instance의 variant ID는 유지되고 `degraded` 상태가 된다. 즉석 임시 병종은 만들지 않는다.
- 해번 양부는 하나의 모집 병종 아래 `(formation.jiefan_liangbu, LEFT)`와 `(formation.jiefan_liangbu, RIGHT)`를 각각 cap 1의 cadre slot으로 추적한다. offer, intake, reservation, instance, replay가 같은 `cadreSlotId`를 전달한다.

### 4.1 초기 directed refit matrix

`A ↔ B`는 서로 다른 두 `RefitEdge`를 뜻한다. `완전 양방향`은 표에 적힌 모든 형제 쌍을 각각 두 방향 edge로 materialize한다. 각 edge는 시설, 반환 장비, 새 장비, 기간, 숙련 보존율, profile을 별도 행으로 저장한다.

| 부모 | 허용 edge | 보존·제한 |
|---:|---|---|
| 1 | 도검 ↔ 창, 도검 ↔ 궁, 창 ↔ 궁 | 향리 인원·계보 보존, 무기 교체와 재훈련 |
| 8 | 도끼 ↔ 장병 | 인원 보존, 돌격 숙련 일부 보존 |
| 12 | 투창·궁·노 사이 완전 양방향 | 산개 숙련 보존, 사격 숙련은 무기별 재훈련 |
| 14 | 경기 창기병 ↔ 경기 도기병 | 말·기병 숙련 보존 |
| 30 | 청주 창·극·도검돌격 사이 완전 양방향 | 청주 계보 보존, 돌격 숙련 별도 |
| 38 | 단양 창·궁·노 사이 완전 양방향 | 단양 계보 보존, 사격 숙련 재훈련 |
| 46 | 판순 방패창 ↔ 백죽노 | 백죽노 claim·재고가 `FIXTURE_GREEN`일 때만 |
| 47 | 양주 경기·궁기·철기 사이 완전 양방향 | 말 보존, 갑주·활·충격 훈련 교체 |
| 57 | 가설교 ↔ 부교 | 공병 계보 보존, 교량/부교 자재 원자 교환 |
| 63 | 등갑 창방패 ↔ 등갑 산병 | `CLASSIC`, 등갑 보존, 대형·사격 재훈련 |
| 77 | 황건 도·창·궁 사이 완전 양방향 | 살아 있는 조직망과 인원 보존 |
| 78 | 흑산 투창·방패·궁 사이 완전 양방향 | 산악 숙련 보존, 무기별 재훈련 |

`#55` 공성 플랫폼, `#61` 선박 흘수, `#62` 함대 플랫폼, `#64` 코끼리 임무형은 현장 refit을 금지한다. 생산 거점에서 기존 플랫폼을 반환하고 새 asset·승조원을 예약하는 `RECONSTITUTE` operation만 허용한다. 그 밖의 단일 자식 부모에는 sibling refit edge가 없다.

## 5. 중복·경계·profile·에셋

### 중복 fingerprint

1. `recipeFingerprint`: 이동, capability/order, 무기·방호 행동, 교리, 보급·승조원 구조, footprint를 정규화한다.
2. `offerFingerprint`: recipe fingerprint에 모집 조건, 상한·계보, profile, 획득·개편 의미를 더한다. 이름, 그림, 품질, 가격·수치 배율은 제외한다.

동일 recipe를 여러 전통이 공유할 수는 있지만 모집원, 계보, 상한, 정치 위험 중 실제로 검증되는 차이가 있어야 한다. 이름과 그림만 다른 `ACTIVE` offer는 validator가 거부한다.

각 variant는 최소 `identityClaimIds[]`, `mobilityClaimIds[]`, `weaponClaimIds[]`, `roleClaimIds[]`를 분리해 가지며 각 claim은 `PRIMARY_ATTESTED | SCHOLARLY_RECONSTRUCTION | ROMANCE_ATTESTED | GAME_REFERENCE | BALANCE_ONLY` 중 하나를 명시한다. 한 차원의 강한 근거가 다른 차원의 무장·역할을 자동으로 정당화하지 않는다.

### 소유 경계

- #17 야전 축성공병은 진지·장애물·수리, #56 굴착 공병은 성벽 기초와 대응 갱도를 소유한다.
- #18은 convoy를 지키는 전투 인력, #59는 짐승 기반 군량, #60은 도로 기반 중량 수레를 소유한다.
- #50은 선박에 타는 강동 육전 인력, #62는 선박과 승조원을 묶은 fleet 플랫폼을 소유한다.
- #52는 둔전 사이의 변경 경로, #58은 둔전 생산 거점과 창고를 지킨다.
- #34 해번 좌·우는 계보 slot 차이이며 무장·명령이 같은 중복 병종 두 개가 아니다.
- 발석거와 벽력거, 목우와 유마, 동물 종, 화물 종류, 선박 크기만으로 자동 분리하지 않는다.

### content profile과 에셋

- 부모와 variant의 profile policy 교집합이 최종 허용 범위다. world와 replay는 content profile, catalog revision, asset release를 고정한다.
- #63–68과 #70–72는 명시된 `CLASSIC` overlay 없이는 활성화하지 않는다. #69 원융노 운용대는 일반 반복노에 대한 독립 claim과 fixture가 있을 때만 `CHRONICLE`에서 별도로 허용할 수 있다. `CHRONICLE`은 연의·게임·legacy 정의 claim을 fail-closed로 거부한다.
- 80개 부모는 전통 icon·기치·base art를, 105개 variant는 조합형 `VisualRecipe`를 가진다.
- 보행 근접·장병·사격·기마·수송·공성·선박 animation chassis는 공유한다. 무기·방호 overlay, 지역 양식, faction mask, anchor, LOD, manifest hash를 variant recipe에서 고정한다.
- 동시에 보이는 형제 variant는 64px에서 무장·실루엣·기치로 구분돼야 한다. 구분되지 않으면 합치거나 visual recipe를 다시 설계한다.
- sprite alpha, frame 크기, 색상으로 gameplay 수치·footprint·피아 판정을 만들지 않는다.

## 6. 채택·구현 검증 조건

- named 부모의 번호 집합이 정확히 `1..80`이고 `BUDGET_ONLY` `CatalogBudgetSlot` 48개가 부모나 variant로 섞이지 않는다.
- `ACTIVE` 부모마다 `ACTIVE` variant가 하나 이상 있으며 총 105개 ID와 표시명이 유일하다.
- orphan variant, 해소되지 않은 recipe·claim·policy·refit edge·visual manifest 참조가 없다.
- 부모와 자식이 `NAMED → CLAIMED → FIXTURE_GREEN → ACTIVE` lifecycle을 건너뛰지 않는다.
- recipe/offer fingerprint collision test가 같은 부모 중복과 이름·그림만 다른 전역 중복을 거부한다.
- 품질, 경험, 상태, 병력, 탄약, 가격, tint, source를 바꿔도 새 variant ID가 생기지 않는다.
- player/AI resolver와 intake/engine precheck의 allow/deny·reason code가 같은 snapshot에서 일치한다.
- 대표 `CHRONICLE`·`CLASSIC`·지역·cadre 도시 fixture가 기대한 모집 행을 만들고 generic fallback이나 silent truncation이 없다.
- 상한 경합에서 마지막 slot은 정확히 한 요청만 얻고, 형제 개편으로 cadre나 상한을 세탁하지 못한다.
- 개편은 병력·장비·보급 보존과 replay event를 증명한다.
- 모든 `ACTIVE` variant가 visual manifest와 checksum을 해소하고 형제끼리 64px에서 판독된다.
- 동일 catalog 입력을 두 번 build한 ordered manifest와 hash가 byte-identical하다.
- 알 수 없는 v1 숫자 ID는 거부되며 v2 semantic ID나 sprite ID로 승격되지 않는다.

이 문서는 목록과 계약을 확정하는 설계 제안이다. 105개 모두가 이미 구현·고증·에셋 승인됐다는 완료 선언이 아니다.
