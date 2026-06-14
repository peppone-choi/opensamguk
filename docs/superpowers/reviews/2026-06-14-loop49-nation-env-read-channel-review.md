# Cross-agent critique — 바퀴49 nation_env(V3) read 채널 (W1-O #1 / NF-P1-B)

- **날짜**: 2026-06-14
- **브랜치**: `loop-parity-2026-06-14-c` → `main`
- **범위**: 백엔드 read-only (`app/game-api` + `infra` 엔티티). 데몬/flush/월틱/ChangeRecorder **무변경**(one-daemon-write-rule 불위반, turn-freeze 리스크 0).

## 대상 변경

데몬은 `nationNotice`/`scout_msg`/`available_war_setting_cnt`를 V3 `nation_env` 테이블(int-namespace = nationId)에 flush(`ChangeRecorder.recordNationEnvKv` → `JdbcFlushExecutor.nationEnvKvWrite`)하나, 기존 `GameKvReadRepository`는 V7 `game_kv`(string-namespace) 테이블만 읽어 nation_env를 못 봤다(테이블 미스매치 = NF-P1-B/P0-C 근본원인).

- NEW `infra/src/main/kotlin/opensamguk/infra/entity/NationEnvEntity.kt` — `@Table("nation_env")`, namespace:Int, key:text, value:jsonb→String. (이 `infra/src` 엔티티의 JPA 매핑 정합을 grader-w49가 V3 스키마 대조로 검증; round-trip은 game-api `NationEnvReadIT`가 CI에서 확증.)
- NEW `app/game-api/.../read/NationEnvReadRepository.kt` — `findByNamespaceAndKey` (read 전용).
- MOD `NationFinanceController.kt` — nationMsg=`nationNotice.msg` / scoutMsg=`scout_msg` / warSettingCnt.remain=`available_war_setting_cnt` 디코드 배선(부재→null).
- MOD `F4ReadControllersTest.kt` — nationEnv mock + 신규 populated 테스트(+ 기존 null 동작 핀 보존).
- NEW `NationEnvReadIT.kt` — 실DB round-trip(@DataJpaTest + Testcontainers).

## Cross-agent critique — `grader-w49` (ce-correctness, fresh 적대)

로컬 Testcontainers IT가 **Docker daemon 미가동**으로 실행 불가 → 그래더가 round-trip IT의 JPA-매핑 검증을 **소스 인inspection으로 대체**(엄밀 지시).

1. **JPA 매핑(CRITICAL)**: V3 스키마(`id serial PK, namespace integer, key text, value jsonb, UNIQUE(namespace,key)`)와 `NationEnvEntity` 정확 일치 — name/Int↔integer/String↔text/value+columnDefinition=jsonb/Int?+IDENTITY↔serial. `ddl-auto: validate`가 불일치 시 컨텍스트 기동 거부하는데 불일치 없음. `game_kv`의 `table` 컬럼 부재가 올바름(nation_env엔 없음). **PASS**.
2. **Discovery**: `GameApiApplication` `@EntityScan`/`@EnableJpaRepositories` = `[opensamguk.infra, opensamguk.gameapi.read, opensamguk.gameapi.owner]` → 엔티티/repo 모두 커버. **PASS**.
3. **decode↔encode**: nationNotice 객체→`.get("msg").asText()`, scout_msg String→`MetaJson.encode`로 JSON 문자열→`asText()`, available_war_setting_cnt Int→bare int→`takeIf{isNumber}.asInt()`. 3종 모두 `NationFinanceSetterHandler` write 인코딩과 정합. **PASS**.
4. **테스트 fidelity**: IT seed jsonb 리터럴이 encode 형태와 일치, ReadRepositoryIT와 동일한 `@DataJpaTest`+Testcontainers+Flyway-V3 패턴(CI 실행·Docker 없으면 skip). 기존 null-동작 테스트(`doesNotExist`) 보존·미완화, 신규 테스트는 populated 단언. **PASS**.
5. **스코프**: read-only — game-engine/flush/ChangeRecorder/월틱 무변경, repo write 메서드 없음, editable 권한게이트·nationsList=null 미변경. **PASS**.
6. **무날조**: 부재 KV→null(nullable + getOrNull), 위조 0/"" 없음. **PASS**.

잔여 [LOW, 비차단]: `nationEnvNode`가 `runCatching{}.getOrNull()`로 파싱실패를 null로 흡수 — malformed jsonb가 absent로 보일 수 있으나 데몬이 유일 writer로 항상 유효 `MetaJson` jsonb 방출이라 실무상 비발생.

## 게이트 증거

- game-api 유닛: `F4ReadControllersTest` **28/28**(신규 populated + 기존 null 핀). 컴파일 green.
- round-trip 실DB: `NationEnvReadIT` 작성. **로컬 Docker 미가동 → CI jvm(Docker)에서 실DB 검증**(이 PR의 jvm check가 게이트 — red면 머지 차단). 동일 패턴 ReadRepositoryIT 선례로 CI 실행 보장.
- 골든/테스트 완화 0. 날조 0.

## Verdict: cleared

블로커/HIGH 0. read-only(turn-freeze 무) + 유닛 green + 그래더 정밀 매핑 검증 PASS. round-trip 최종 확증은 CI jvm(Docker)에서. 머지/배포는 PR jvm check green 선결.
