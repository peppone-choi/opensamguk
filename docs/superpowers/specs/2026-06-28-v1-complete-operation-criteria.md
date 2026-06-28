# 오픈삼국 v1 전체 동작 기준

> 작성일: 2026-06-28
> 상태: draft-gate
> 목적: v2 착수 전에 v1이 "완벽하게 동작한다"고 말할 수 있는 판정 기준을 고정한다.

## 1. 판정 원칙

v1 완성은 기능 존재가 아니라 **같은 입력이 같은 결과로 반복되는 운영 가능 상태**다. 아래 항목 중 하나라도 실패하면 v1은 완벽하지 않으며, v2 작업은 설계 문서까지만 허용하고 구현 착수는 보류한다.

1. PHP grand truth와 패러티 게이트를 완화하지 않는다.
2. 명령은 등록, 노출, 예약, 데몬 소비, 결과 조회까지 한 흐름으로 검증한다.
3. 전투는 시뮬레이터가 아니라 실제 `che_출병` 경로와 정복 side effect까지 검증한다.
4. 거병과 건국은 즉시/예약 seam 양쪽에서 검증한다.
5. 멸망, 계승, 천하통일 상태가 재기동 후에도 보존되는지 검증한다.
6. 웹 UI와 프로덕션 표면에서 사용자가 실제로 같은 흐름을 관측할 수 있어야 한다.

## 2. 필수 게이트

### 2.1 백엔드 패러티 게이트

명령:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) tools/parity/gate.sh backend
```

합격 기준:

- 명령 출력에 `BUILD SUCCESSFUL`이 있어야 한다.
- 테스트 XML에서 failure/error가 0이어야 한다.
- `tools/parity/gate.sh backend` 자체를 완화하지 않는다.
- UP-TO-DATE false-green 의심 시 `--rerun-tasks`로 재확인한다.

### 2.2 명령 등록/노출/예약/소비

필수 검증:

- `CommandRegistryTest`: 모든 구현 명령이 레지스트리에 등록된다.
- `MissingAiCommandDefsTest`: AI/예약 경로가 참조하는 명령 정의가 비어 있지 않다.
- `CommandWireMapperTest`: 프론트/API wire code가 데몬 명령으로 매핑된다.
- `AvailableCommandsControllerTest`: 사용자가 실행 가능한 명령 목록을 받는다.
- `CommandControllerIT`: API가 예약 명령을 받아 저장한다.
- `CommandReserveServiceIT` 또는 `ReservedTurnRepositoryIT`: 예약 슬롯이 DB에 안정적으로 저장된다.
- `ReservedTurnHandlerTest`: 데몬이 예약 명령을 실제 로직 resolver로 소비한다.
- `CommandResultLookupTest`와 결과 채널 테스트: 비동기 성공/실패 결과를 사용자가 조회할 수 있다.

합격 기준:

- 등록된 명령 수와 wire code가 서로 어긋나지 않는다.
- precheck deny reason과 데몬 deny reason이 같은 의미와 문자열로 유지된다.
- 존재하지 않는 명령, 인자 누락, 권한 부족은 500이 아니라 명시적 deny/result가 된다.

### 2.3 전투와 정복

필수 검증:

- `BattleReplayGateTest`: PHP 캡처와 전투 RNG draw/order/log/numeric 결과가 일치한다.
- `ConquerCityReplayGateTest`: 정복 후 도시, 국가, 장수, 로그 side effect가 일치한다.
- `ProcessWarNGOrderTest`: phase별 RNG draw 순서가 고정된다.
- `ProcessWarWrapperTest`: 보급, 성벽, 쌀, 정복 후 처리 wrapper가 유지된다.
- `BattleCommandContextBuilderTest`: 실제 출병 명령이 전투 context를 구성한다.
- 실제 `che_출병` 예약이 데몬에서 소비되어 전투 로그와 DB 변경을 만든다.

합격 기준:

- 전투가 단순 preview만 통과하면 불합격이다.
- 공격/수비/도시수비/정복 후 처리 중 하나라도 write path가 누락되면 불합격이다.
- 전투 로그 순서와 damage/dead/killed 수치가 PHP 골든과 어긋나면 불합격이다.

### 2.4 거병과 건국

필수 검증:

- `GeobyeongTest`: 거병 순수 로직이 PHP 골든과 일치한다.
- `FoundingGoldenTest`: 건국 결과 row set과 로그가 PHP 골든과 일치한다.
- `PresetsFoundingTest`: 건국 명령 precheck/preset 조건이 유지된다.
- `FoundingHandlerSeamTest`: 데몬 예약 경로에서 `che_거병`/`che_건국`이 crash 없이 실행된다.
- `JdbcFlushExecutorIT`: created nation/diplomacy/general/city/log row가 JDBC flush로 저장된다.

합격 기준:

- 신규 세력 ID, 외교 row, 도시 소유권, 장수 소속, 로그가 한 flush 안에서 정합해야 한다.
- 이미 세력에 속한 장수, 도시 조건 불충족, 인자 누락은 명시적 deny가 되어야 한다.
- 즉시 intake와 예약 turn seam 중 하나만 통과하면 불합격이다.

### 2.5 멸망, 계승, 천하통일

필수 검증:

- `RulerSuccessionTest`: 군주 사망/부재 시 후계자 선정 또는 멸망 처리가 정해진다.
- `ChangeRecorderNationTest`: 멸망한 국가와 도시 tombstone/dirty set이 flush 대상으로 잡힌다.
- `UpdateNationLevel*Test`: 도시 수 변화가 국가 레벨과 보상 루프에 반영된다.
- `MonthTickReplayGateTest`: 월간 처리 후 국가/도시/장수 상태가 PHP 골든과 일치한다.
- `LongSimReplayGateTest`: 장기 시뮬레이션이 멈추지 않고 상태가 수렴한다.
- `world_state.isunited`가 true가 되면 DB에 저장되고 재기동 후 유지된다.

합격 기준:

- 마지막 경쟁 세력 멸망 후 천하통일 상태가 기록되어야 한다.
- 천하통일 후 금지되어야 하는 명령은 deny되어야 한다.
- 단기 테스트만 통과하고 장기 시뮬레이션이 freeze되면 불합격이다.

### 2.6 웹 UI와 프로덕션 표면

필수 검증:

- `web/gateway` 로그인, 회원가입, 로비, 어드민 접근이 500 없이 동작한다.
- `web/game` 메인에서 지도, 내 장수, 도시, 최근 기록, 명령 예약 UI가 로드된다.
- 명령 예약 후 결과가 UI 또는 API 결과 채널에서 확인된다.
- 전투 발생 후 전쟁/정세 로그가 화면에 보인다.
- 프로덕션 `https://sam.peppone.dev/health`, gateway-api health, game-api health가 모두 성공한다.
- fresh account 또는 known admin으로 로그인부터 게임 진입까지 확인한다.

합격 기준:

- 로컬만 통과하고 프로덕션 로그인 또는 게임 진입이 실패하면 운영 기준 미달이다.
- API가 200이어도 UI가 사용자가 이해할 수 없는 빈 상태면 불합격이다.
- 500, Cloudflare 5xx, 미처리 promise rejection, hydration error가 있으면 불합격이다.

## 3. v2 착수 조건

v2 구현 착수는 아래가 모두 참일 때만 허용한다.

1. `tools/parity/gate.sh backend` 통과.
2. 명령/전투/거병/건국/천하통일 핵심 테스트 묶음 통과.
3. `web/gateway`와 `web/game` typecheck 또는 build 통과.
4. 로컬 스모크 또는 실제 프로덕션에서 로그인→게임 진입→명령 예약→결과 관측 성공.
5. 실패/미구현 항목이 있으면 `docs/superpowers/gap/` 또는 v1 기준 문서에 명시하고, v2 구현은 보류.

## 4. 현재 판정 기록 형식

매번 v1 판정을 갱신할 때 아래 표를 채운다.

| 축 | 증거 | 결과 | 남은 위험 |
|---|---|---|---|
| backend parity | 2026-06-28 KST `tools/parity/gate.sh backend`: `BUILD SUCCESSFUL`, XML 438 suites / 3410 tests / failures 0 / errors 0 / skipped 1 | pass-with-risk | `LongSimReplayGateTest` 구조 리플레이 1건은 여전히 disabled |
| command lifecycle | `CommandContractMatrixTest` 187, `CommandRegistryTest` 5, `MissingAiCommandDefsTest` 19, game-api command lifecycle 24, engine reserved handler 7 | pass | 전체 API→예약→데몬→결과 e2e UI 조작은 별도 prod smoke 필요 |
| battle/conquest | `BattleReplayGateTest`, `ConquerCityReplayGateTest`, `ProcessWarNGOrderTest`, `ProcessWarWrapperTest`, `BattleCommandContextBuilderTest`: focused XML failures 0 | pass | 실제 장기 NPC 전쟁 빈도/수렴은 long-sim disabled와 연결 |
| founding | `GeobyeongTest`, `GeongukTest`, `FoundingGoldenTest`, `PresetsFoundingTest`, `FoundingHandlerSeamTest` 7 tests, `JdbcFlushExecutorIT --rerun-tasks`: failures 0 | pass | 이번 바퀴에서 `che_건국`/`cr_건국`/`che_무작위건국` daemon seam을 새로 잠금 |
| unification convergence | `RulerSuccessionTest`, `ChangeRecorderNationTest`, `UpdateNationLevel*Test`, `MonthTickReplayGateTest`, `ScenarioBootIT`/`isunited` boot-load, backend gate XML failures 0 | blocked | `LongSimReplayGateTest.12 month structural replay matches PHP golden()` disabled: PHP 12국 vs Kotlin 5국 장기 AI/founding 수렴 갭 |
| web/prod surface | `web/gateway` and `web/game`: `tsc --noEmit` pass, `next build` pass | partial | prod 로그인→게임 진입→명령 예약→결과 관측은 이번 코드 배포 후 재확인 필요; build warnings는 남아 있음 |
