# OPENSAM-149 restart-rehydrate 왕복 게이트 리뷰

Date: 2026-07-26
Scope: `app/`, `logic/` (`logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt` S5 카탈로그 등재 포함), `docs/` — OPENSAM-149 1단계(troop 왕복 폐쇄 + D1 재분류 + 왕복 게이트 신설).
Reviewer: CodeRabbit (독립 프로바이더, PR #332 자동 리뷰) — 지적 2건 제기, 둘 다 수정 완료.
Verdict: cleared

## 리뷰 대상

- `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/turn/RehydrateService.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/RehydrateRoundTripIT.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/RehydrateWiringTest.kt`
- `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`
- `docs/superpowers/gap/LOGIC_GAP.md` §15
- `docs/superpowers/research/2026-07-25-opensam-149-rehydrate-defects.md` §5

## 제기된 지적과 처리

### 1. (MINOR) `RehydrateService.kt` KDoc 재분류 날짜 오기 — **수정됨**

`SUPERSEDED ... (OPENSAM-149 D1, 2026-07-25)`로 적혀 있었으나 재분류는 2026-07-26에 이뤄졌다.
연구 문서 §5·`LOGIC_GAP` §15와 불일치. `2026-07-26`으로 정정.

### 2. (MAJOR) `RehydrateWiringTest`의 단정이 `DaemonLoopConfig.kt` **전체 소스**를 대상으로 함 — **수정됨**

재조준한 게이트가 여전히 헐거웠다. 시드 로직이 살아있는 `turnRunService` 빈 밖(죽은 코드나 미배선
헬퍼)으로 빠져나가도 파일 어딘가에 문자열만 남아 있으면 green으로 남는다.

수정: `turnRunServiceBody()`가 `turnRunService` 빈 본문만 잘라내고(기존
`HotColdWorldCatalogGuardTest.privateMethodBody` 관용구 그대로 다음 최상위 선언까지 슬라이스) 세 단정
모두 그 스코프에서만 검사한다. 슬라이스가 전체 파일로 퇴화하면 단정이 공허하게 참이 되므로, 슬라이스
앞에 선언된 `fun realtimePublisher(`가 본문에 없음을 확인하는 **자체 검증**을 함께 넣었다.

## 검토된 위험 지점

- **ONE daemon-write rule** — 위반 없음. 추가된 것은 부팅 시 read(`loadTroops`) 하나이고, 쓰기 경로는
  건드리지 않았다. JPA EntityManager 미사용.
- **flush delta** — 변경 없음. 인라인 DB 쓰기 추가 없음.
- **RNG / 반올림 / 한국어 로그 패리티** — 해당 없음. RNG draw, `PhpRound`, 로그 문자열 경로를 건드리지
  않는다.
- **골든 날조** — 없음. 새 테스트는 PHP 골든이 아니라 실제 `JdbcFlushExecutor`(writer) ↔ 실제
  `WorldSnapshotLoader`(reader) 왕복을 Testcontainers에서 검증한다. 골든 수정·약화 없음.
- **S5 bounded boot reads** — `loadTroops`는 `WHERE world_id = ?`로 월드 스코프되고
  `ORDER BY troop_leader ASC`로 결정론적이다. `HotColdCatalog`에 `troop` / `ALWAYS_HOT` /
  `HOT_ENTITY_SET`으로 등재했고 `HotColdWorldCatalogGuardTest`가 미등재 JDBC 호출을 차단한다
  (실제로 이 가드가 초기 커밋을 잡아냈다).
- **Docker-skip 가림** — `RehydrateRoundTripIT`는 `skipped="0"`으로 실행됨을 확인했다. 스킵으로 green을
  위장하지 않았다.

## 커버되지 않은 각도 (정직한 한계)

내부 비평 에이전트를 별도로 붙였으나 **보고 본문을 전달하지 못했다**(이 세션에서 반복된 채널 장애).
따라서 아래는 CodeRabbit 리뷰와 CI, 작성자 자체 조사로만 검증됐고 **독립 심층 공격을 받지 않았다**:

- D1 재분류의 의미론적 정당성 — 즉 P6 게이트 4번 항목이 리포지토리 on-demand read로 **정말 빠짐없이**
  커버되는지. 항목별 대조표는 연구 문서 §5.1에 `file:line`과 함께 남겼으나, 제3자가 각 사용처를
  전수 확인하지는 않았다.
- `messageRepository.findMaxId()`의 월드 스코프 여부 — 다중 월드에서 id 할당자가 올바른지는 이번
  변경 범위 밖이며 미검증. 별건으로 확인 대상.
- `troop` 테이블에 인메모리 `Troop`(id/nationId/name)이 싣지 않는 컬럼이 있어 왕복이 여전히 손실적일
  가능성 — 현재 스키마(`V1__baseline.sql` `troop_leader`/`nation`/`name`)상 3컬럼 전부를 싣지만,
  이후 마이그레이션으로 컬럼이 추가되면 조용히 손실된다. 회귀 방지 장치 없음.

이 한계들은 은폐하지 않고 남긴다. 후속 확인 대상이며, 현재 변경이 **기존 상태보다 나쁘게 만드는**
지점은 발견되지 않았다.

## 검증 증거

```
game-engine  tests=639  skipped=1  failures=0  errors=0   (로컬, --rerun-tasks)
logic        tests=3110 skipped=0  failures=0  errors=0   (로컬, --rerun-tasks)
jvm CI       pass 7m30s                                   (PR #332)
web (game) / web (gateway) CI  pass
RehydrateRoundTripIT   tests=3 skipped=0                  (Docker 실기동)
RehydrateWiringTest    tests=2 skipped=0
```

수정 전 동일 셀은 `expected: [Troop(id=11...), Troop(id=12...)] but was: []`로 실패했고, 선행 단정
`troopRowsInDb`는 통과했다 — 쓰기는 되는데 읽기가 없다는 실측.

`claude-review` 체크는 fail이나 리뷰 지적이 아니라 봇 API 장애다
(`"subtype":"success","is_error":true`, 0.2초 종료). PR #331에서 동일 증상이 관측됐다.
