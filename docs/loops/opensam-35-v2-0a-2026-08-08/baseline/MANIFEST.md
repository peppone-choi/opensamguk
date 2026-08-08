# OPENSAM-35 (0A-g) 기준선 artifact MANIFEST

계획 §1 M3 규약: `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/` 아래 4종 + 본 MANIFEST에 sha256.

- 생성 시각: 2026-08-08
- 리포 상태: final backend A4는 false-green 3건 remediation을 모두 반영한 working tree에서 실행했고,
  이후 변경은 compose relay와 문서 동기화뿐이라 backend source/test 입력은 바뀌지 않았다.
- 최종 브랜치는 `origin/main=b847c351`에서 OPENSAM-35 변경을 단일 커밋으로 재구성했다.

## 파일 sha256

| 파일 | sha256 |
|---|---|
| `a1-v1-schema-dump.sql` | `de16ba5bd3c5f531021e65b9432c761b3a296dd6cb3bcf1ff23e81259be2ce50` |
| `a1-v1-flyway-migration-sha256.txt` | `6888b07802d988a1e6d64fe974d6056f053e33022091ac60da94ef69d964bceb` |
| `a2-scenario-seed-sha256.txt` | `f42d3a4f935be3a63de1524f146819c7b9bc1160c0b3aa255f7eb57a32bbbb67` |
| `a3-php-golden-inventory-sha256.txt` | `229ee5cdb3f2c4e612d4593bf57eb1af83983cbb51189dfe990eb1a8fe15f233` |
| `a4-backend-gate.log` | `743b85a3cb60aa9400e49513f766f4db56358c50547a8de57f74b2233fa15a32` |
| `a4-backend-gate-xml-summary.txt` | `fa43bb8a7ae53c71e9ed8a58887cbd2cf4d7935b84ac682da1d3314ba9e849ce` |
| `a4-web-gate.log` | `d803e60c5410b69409e98ed20c21a7b8661d313da8e17e91072252422fd3ca32` |

재검증:

```
cd docs/loops/opensam-35-v2-0a-2026-08-08/baseline && shasum -a 256 * | sort -k2
```

전 파일 100KB 미만이므로 M3의 "대용량 덤프는 gitignore" 예외는 적용하지 않는다. 전부 커밋 대상.

---

## A1. v1 schema dump — **생성됨 (실측)**

`a1-v1-schema-dump.sql` — 45 테이블 / 49 인덱스 / 3204행.

생성 방법 (재현 가능, 지어낸 값 0):

1. `postgres:16-alpine`를 비표준 포트 55435로 일회성 기동 (컨테이너명 `opensam35-s6-v1schema`)
2. `infra/src/main/resources/db/migration/V*.sql` 37개를 **Flyway 버전 순서**(`sort -t_ -k1,1n`)로 적용.
   각 파일을 `BEGIN;`/`COMMIT;`로 감싸 Flyway의 파일당 1트랜잭션 의미를 재현하고,
   `executeInTransaction=false`인 `V29__log_entry_year_month_index.sql`만 비트랜잭션으로 적용.
   적용 실패 0건.
3. `pg_dump --schema-only --no-owner --no-privileges --no-comments`
4. 비결정 라인 제거: `-- Dumped …`, `-- PostgreSQL database dump…`, `\restrict`/`\unrestrict`
   (pg_dump가 실행마다 새로 뽑는 난스). 저장소 whitespace gate에 맞춰 EOF의 빈 줄 2개도 제거했다.
   **정규화 후 2회 연속 덤프가 byte-identical함을 확인.**
5. 컨테이너 삭제 확인 (`docker ps -a | grep opensam35` → 0건).

**한계 (정직 고지):** 이것은 Flyway가 실제로 적용한 스키마가 *아니라* psql 재현본이다.
`flyway_schema_history` 테이블은 포함되지 않는다(Flyway가 만드는 것이라 psql 경로에는 생기지 않는다).
DDL 자체는 동일 SQL 파일·동일 순서이므로 스키마 형상은 일치한다.

`a1-v1-flyway-migration-sha256.txt` — 마이그레이션 **원본 37개**(V*.sql + V29 .conf)의 파일별 sha256.
스키마 dump가 어떤 입력에서 나왔는지 고정한다.

## A2. seed hash — **생성됨 (실측)**

`a2-scenario-seed-sha256.txt` — `data/extracted/scenario/`의 tracked 시드 소스 **82개**
(`scenario_*.json` 81 + `_meta.json`) 파일별 sha256.

**한계:** RTK14 5스탯 생성본(`SCENARIO_DIR` 오버라이드용)은 CLAUDE.md 규약상 **git-ignore·미커밋**이라
기준선에 포함하지 않는다. 라이브 DB에 실제 시드된 행의 해시가 아니라 **시드 소스 파일** 해시다.

## A3. PHP golden — **신규 캡처 없음 = "해당 없음"** (근거 있음, 미생성 아님)

이 티켓은 PHP 오라클과 **접점이 0이다.** 근거 3종, 전부 실측:

1. **T1 diff 0** — `logic/src/main/kotlin/`·`common/src/main/kotlin/`·`logic/src/test/resources/golden/`
   수정/삭제 0건 (게이트 ②, merge-base 및 origin/main 양쪽 빈 출력).
2. **golden 트리 해시 동일** —
   `git rev-parse HEAD:logic/src/test/resources/golden` = `origin/main:…` = `3650b814950fb9f0d784ae1e4031a05658919ea4`.
   워킹트리도 golden 경로에 변경 0건.
3. **v2 소스에 패리티 커널 참조 0건** — v2 Kotlin 10개 파일 전체 grep 결과
   `import opensamguk.logic`·`import opensamguk.common`·`RandUtil`·`PhpRound`·`LiteHashDrbg`·
   `ConvertLog`·`Josa` **전부 0 hit**. RNG draw·라운딩·한글 로그 경로를 하나도 건드리지 않는다.

즉 새 골든이 **필요하지 않다**(≠ 필요한데 못 했다). 골든 불변성은 게이트 ①의
`:logic:test` 3173건(골든 리플레이 포함) 전부 green으로 별도 입증된다.

`a3-php-golden-inventory-sha256.txt` — 골든 파일 **273개**의 파일별 sha256. 향후 회귀 비교용 고정값.

## A4. backend / web gate — **생성됨 (실측)**

- `a4-backend-gate.log` — remediation 뒤 `tools/parity/gate.sh backend` 최종 전체 출력.
  첫 실행의 Docker API HTTP 500은 Testcontainers 환경 오류로 격리했고, Docker 정상화 뒤 단 한 번
  재실행한 결과는 `BUILD SUCCESSFUL in 19m 36s`였다.
- `a4-backend-gate-xml-summary.txt` — Gradle test XML 독립 집계.
  gateway-api까지 포함한 599 suites / 5023 tests / failures 0 / errors 0 / skipped 1.
  skipped 1 = `opensamguk.engine.golden.LongSimReplayGateTest`(CLAUDE.md에 기록된 **기존** 백로그
  "long-sim multi-turn (gate dim c)"). 이번 티켓이 만든 skip이 아니다.
- `a4-web-gate.log` — `cd web/game && pnpm typecheck && pnpm test`.
  typecheck 무출력 통과, 54 files / 288 tests 전부 pass. `__tests__/v2-lab-route.test.tsx` 17건 포함.

---

## UNKNOWN / 미측정

- **과거 `a4-web-gate.log` 테스트 개수 변동** — remediation 전 3회 실행은 284/287/287로
  달랐고 원인은 UNKNOWN이었다. 현재 artifact는 remediation 뒤 fresh 288건 출력이며 전부 pass다.
- **라이브 Flyway 적용 스키마 dump** — A1은 psql 재현본이며 `flyway_schema_history` 미포함.
  실 Flyway 부팅 dump는 미생성.
- **v2 마이그레이션 적용 후 스키마** — `db/migration_v2/`는 README뿐이라 적용할 것이 없다.
  버전 번호 정책은 OPENSAM-150(R1) 소관으로 계획서 §S1이 미확정 처리했다.
