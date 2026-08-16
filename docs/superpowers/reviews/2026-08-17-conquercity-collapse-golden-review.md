# OPENSAM-186 — 정복-멸망 골든 캡처 + 로그 게이트

Scope: `tools/php-golden/capture_conquercity.php` 의 기존 collapse 브랜치를 Docker 하니스로 실제 캡처해
`logic/src/test/resources/golden/p4/conquercity-collapse-{full,only-random}-01.json` 을 커밋하고,
`ConquerCityCollapseTest` 에 그 골든 기반 멸망 로그 3종 문자열·순서 assert 를 추가했다.

Verdict: PASS — 두 번의 fresh-DB 런에서 5개 픽스처 전부 sha256 동일, 신규 골든 테스트는 멸망 로그 발행을
제거하면 RED(2건) / 복원하면 GREEN(11/11), 기존 골든 파일은 한 바이트도 수정하지 않았다.

## 증거

- 캡처: MariaDB 11.4 + `php:8.3-cli`, `ResetHelper::buildScenario(...1010...)` 브랜치마다 fresh install,
  `UniqueConst::$hiddenSeed` = `8ebfeb6fa932a181ec9ef43b7473f4c9` (기존 P4 캡처와 동일한 PIN 입력).
- 두 런 sha256:
  - `conquercity-collapse-full-01.json` `1b61e4294a9545594b8ef59bcaa98ff4228c4256bf31643b2d07fd47e44efb6b`
  - `conquercity-collapse-only-random-01.json` `7e53c19ac98f76d8b721740de8ba33183ab0ffc8f29d8e2ab0727721bf6665b9`
  - 대조군: `conflict-01.json` / `conquercity-survive-01.json` / `conquercity-capital-01.json` 도 두 런 동일.
    커밋된 survive/capital 골든과의 차이는 이후 스크립트 개정이 추가한 스냅샷 테이블(`rank_data`,
    `general_turn`, `event`, `message`, `world_history`) + `joinMode` 필드뿐이며 기존 수치는 전부 동일 →
    기존 골든은 손대지 않았다.
- 캡처된 멸망 로그 3종 (황건적, `func.php:1729`/`:1772`/`:1773`):
  - global history — `<R><b>【멸망】</b></><D><b>황건적</b></>은 <R>멸망</>했습니다.`
  - 장수 action — `<D><b>황건적</b></>이 <R>멸망</>했습니다.`
  - 장수 history — `<D><b>황건적</b></>이 <R>멸망</>`
  - 장수 순서 `[8, 9, 19, 37, 58, 71, 76, 106, 108, 110, 115, 129, 134, 136, 137, 151, 157, 160, 105]`
    (타 장수 asc PK + 군주 105 LAST, `func.php:1732-1735`).

## 격리 (유지)

- **CC-1** — collapse 의 금/쌀 `nextRange(0.2,0.5)`·scout `nextBool(0.5)`·NPC 임관 draw 는
  `process_war.php:589` 의 LOCAL rng 에서 나와 grand truth 수정 없이는 기록 불가. 골든은 draw 스트림을
  담지 않고, 신규 테스트도 수치를 단언하지 않는다 — 로그 문자열·발행 순서·장수 순서만 대조한다.
- **CC-2** — 전체 수치 db_delta 재현은 pre-state 월드 전량이 필요해 그대로 남는다.
