# 2026-09-27 #917 — 은퇴 시나리오 파일에서 테스트 떼기 (3단 중 1단)

## 범위

- 클래스패스 `infra/src/main/resources/scenario/`의 삼모 런타임 시나리오 31개(0·1·2, 900–903·905·906·908·910–914, 9200, 1010–1120 15개)를 다음 단계(PR2)가 바이트 그대로 보관 폴더로 `git mv` 할 수 있도록, 그 파일을 클래스패스에서 읽던 Kotlin 테스트를 떼어 냈다.
- 시나리오 JSON·데이터·도구·핀은 건드리지 않았다. 해시·핀 변화 없음.
- PR2 몫으로 남긴 것: 파일 **경로**로 15개를 읽는 `SpatialSupplyProviderTest`·`StrategicSupplyProviderTest`, 은퇴 파일이 클래스패스에 **있다고** 단언하는 `ScenarioCatalogServiceTest`.

## 픽스처 이동

| 테스트 | 전 | 후 |
| --- | --- | --- |
| `ScenarioImporterIT` 월드 id 전파 2건·직접 재진입·동시 시드·빈 월드 게이트·중간 실패 뒤 재시도 | `scenario_1010`(삼모 경로) | `scenario_990002`(제품 기본, 휘하 경로). 월드 id 단언 대상에 `general_spatial_position`·`general_bugok`을 더하고, 휘하가 쓰지 않는 `general_turn`은 0행 단언으로 바꿨다 |
| `ScenarioImporterIT` 휘하 위치 행 | 1010에 `worldFormat`·`lords:["우길"]`을 덧붙인 합성 | `scenario_3190`(군주 21·비군주 243). 군주 행 전부의 `LordStatus`를 확인 |
| `ScenarioImporterIT` 삼모 월드는 위치 행 없음, `extended_general=false` | 1010 | 테스트 리소스 `scenario_mapless_legacy.json`(1010 사본) |
| `ScenarioImporterIT` 프로필 없는 구 시나리오 거부 | `scenario_1020` | `scenario_mapless_legacy.json`, 거부 메시지까지 확인 |
| `ScenarioImporterIT` mapless 시드 | — | 지운 A-minimal 테스트에서 경로 일반 단언 2개(`config.ruleProfile=SAMMO`, `meta.unitSet=han`)를 옮겨 왔다 |
| `ScenarioJsonTest` 군주 선언·프로필 누락·이벤트 순서 | 1010 | `scenario_mapless_legacy.json` |
| `ScenarioJsonTest` 역사 리소스는 휘하 월드가 못 됨 | 개수 하한 `>= 32` | 남을 런타임 시나리오(990002·3190) 포함 확인 + mapless 픽스처로 거부 갈래 고정 |
| `ScenarioJsonTest` ignoreDefaultEvents | `scenario_910` | 인라인 합성 JSON |
| `ScenarioJsonTest` 배포 계약(deploy.yml이 이름으로 실행) | 은퇴 31개 | `scenario_990002`. 이름을 `product runtime scenario declares the HWIHA new world and satisfies its seed contract`로 바꾸고 `deploy.yml`·`tools/rtk14/test_build_rtk14_stats.py`를 같이 고쳤다 |
| `V26NpcLifecycleMigrationTest` | world_state `scenario_1010` → V26이 클래스패스 1010을 읽음 | 1010의 유변·유협 두 행만 옮긴 새 리소스 `scenario_v26_imperial_minors.json` |
| `ScenarioMapSeedIT` 한 세계 도시 목록, 기존 월드 게이트 | `scenario_2`(`LegacySammoScenarioFixture.copyResource`) | 번들 `scenario_990002`. v3 목록 id 전체·표기(`경조윤 장안현`)·국가·장수 수를 확인. `copyResource`를 지웠다(`write`는 3190 테스트가 계속 씀) |
| `EffectiveScenarioResolverTest`, `ScenarioSeedDisabledTest`, `ScenarioTitleResolverTest`, `FrontInfoControllerTest` 제목 해석 | `scenario_1010`·`scenario_1021` | `scenario_990002` — 외부 파일 우선·폴백 금지가 번들 짝이 있어야 성립하므로 |

## 지운 것

- `ScenarioImporterIT`: 9200 V3 소유(9200 내용), A-minimal 행 수(1010 내용·수치), 1030 21세력(1030 내용), `scenario_2` V2 목록(2 내용). 수도=자국 城 정합은 `ScenarioMapSeedIT.assertSeedIntegrity`가 계속 잰다.
- `ScenarioJsonTest`: `scenario_1`·`scenario_2` 맵 계약, 9200 城 id, 1010 명부·황제, 한 시나리오 15개 계약, 911·912 이벤트 수.
- `V2ScenarioSeedTest` 파일 전체(은퇴한 v2 시험장 9200).
- 잃은 커버리지: importer가 `imperialGenerals` 장수에 `npc_state=7`·`meta.imperial`을 쓰는 경로는 이제 IT로 재지 않는다(디코드는 `ScenarioJsonTest`의 합성 JSON이 잰다). 남은 시나리오 중 이 키를 쓰는 것이 없다.

## 같이 고친 것

- `ScenarioJsonTest` 군주 선언: 1010 첫 행(유변, 168년생)은 181년에 원래 비활동이라 「지연·제외하면 거부」 단언이 조작과 무관하게 통과하고 있었다. 활동 장수 `우길`로 바꾸고, 선언 그대로면 계약을 통과한다는 양성 대조를 넣었다.

## 배포 계약에서 3190을 뺀 이유

`deploy.yml`의 RTK14 보강은 `scenario_990002`만 건드리지 않고 `scenario_3190`에는 RTK14 장수를 덧붙인다(`tools/rtk14/build_rtk14_stats.py` `build_one`·`_new_general`, 그림 값이 `<id>.png`). 3190은 `personBonds`가 있어 importer가 시작 활동 장수 전원의 숫자 그림 id를 요구하므로, 보강본을 이 테스트에 넣으면 배포가 빨개질 가능성이 높다(비밀 원본 없이 확인 불가, UNKNOWN). 3190을 운영 카탈로그에 올릴 때(#969 뒤) 보강 제외 또는 importer 쪽을 먼저 정해야 한다.

- [ ] 3190 카탈로그 편입 때 `deploy.yml`의 materialized 시나리오 계약 검사 대상에 3190을 넣고, RTK14 보강본을 입력해 실패 조건이 실제로 감지되는지 확인한다.

## 검증 (JDK 21, Docker 켜짐, Gradle 한 번에 하나)

- `:infra:test` 대상 실행: `ScenarioImporterIT` 24/0/0/0, `ScenarioJsonTest` 20/0/0/0, `EffectiveScenarioResolverTest` 4/0/0/0, `V26NpcLifecycleMigrationTest` 1/0/0/0(tests/skipped/failures/errors, XML mtime이 이번 실행 뒤).
- `:app:game-engine:test` 대상 실행(테스트 소스 전체 컴파일 포함): `ScenarioMapSeedIT` 8/0/0/0, `ScenarioSeedDisabledTest` 3/0/0/0.
- `:app:game-api:test` 대상 실행: `ScenarioTitleResolverTest` 4/0/0/0, `FrontInfoControllerTest` 31/0/0/0.
- PR2 모의(31개를 잠시 클래스패스에서 빼고 돌린 뒤 `git checkout`으로 되돌림, 바이트 동일 확인): `:infra:test` 전체 112클래스 435/0/0/0, `:app:game-api:test` 전체 110클래스 765/0/0/0, `:app:game-engine:test`의 `boot`·`world`·`v2`·`config`·`EmptyWorldBootIT`·`GameEngineApplicationTests` 79클래스 342건 중 실패 3건 — 전부 PR2 몫으로 남긴 `SpatialSupplyProviderTest` 1건·`StrategicSupplyProviderTest` 2건(파일 경로 없음). game-engine 나머지 패키지와 gateway는 이 모의에서 돌리지 않았다.
- `python3 -m unittest`로 `tools/rtk14/test_build_rtk14_stats.py`의 배포 워크플로 테스트 2건 통과.
- naming lint: 기준선 `product_identifier` 990→974, `product_path` 80→79, `package_name` 44→43, `retired_reference` 5844→5841(이번 삭제·수정으로 줄어든 몫, 원래 HEAD는 기준선과 일치). 갱신 뒤 전부 OK.
