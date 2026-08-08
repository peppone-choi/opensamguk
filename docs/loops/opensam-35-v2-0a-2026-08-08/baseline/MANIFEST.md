# OPENSAM-35 (0A-g) 기준선 artifact MANIFEST

계획 §1 M3 규약: `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/` 아래 4종 + 본 MANIFEST에 sha256.

- 생성 시각: 2026-08-08
- 리포 상태: 브랜치 `op-35-v2-0a`, HEAD = `fb90eac1`(= merge-base), 워킹트리에 S1~S6 산출물 미커밋
- **주의:** `origin/main`은 `ad0c8c53`로 HEAD보다 **1커밋 앞서 있다**(behind 1 / ahead 0).
  기준선은 워킹트리 실측값이다.

## 파일 sha256

| 파일 | sha256 |
|---|---|
| `a1-v1-schema-dump.sql` | `eb6f2b736bf39103469bf24328ff14d2b8099196bde331975011b3d2b584959a` |
| `a1-v1-flyway-migration-sha256.txt` | `6888b07802d988a1e6d64fe974d6056f053e33022091ac60da94ef69d964bceb` |
| `a2-scenario-seed-sha256.txt` | `f42d3a4f935be3a63de1524f146819c7b9bc1160c0b3aa255f7eb57a32bbbb67` |
| `a3-php-golden-inventory-sha256.txt` | `229ee5cdb3f2c4e612d4593bf57eb1af83983cbb51189dfe990eb1a8fe15f233` |
| `a4-backend-gate.log` | `3db56563aaa3f99b93777271e97c25008d26b30b2336ff73fa77f35d67c63070` |
| `a4-backend-gate-xml-summary.txt` | `25bdee7ffb24b1cd0a00a3909843059932ab045ca752635de2afad169fe56801` |
| `a4-web-gate.log` | `babd15487a9bdff213cb7eece40ba04d72fdd8a3bc207f149df30f39913dd47e` |

재검증:

```
cd docs/loops/opensam-35-v2-0a-2026-08-08/baseline && shasum -a 256 * | sort -k2
```

전 파일 100KB 미만이므로 M3의 "대용량 덤프는 gitignore" 예외는 적용하지 않는다. 전부 커밋 대상.

---

## A1. v1 schema dump — **생성됨 (실측)**

`a1-v1-schema-dump.sql` — 45 테이블 / 49 인덱스 / 3206행.

생성 방법 (재현 가능, 지어낸 값 0):

1. `postgres:16-alpine`를 비표준 포트 55435로 일회성 기동 (컨테이너명 `opensam35-s6-v1schema`)
2. `infra/src/main/resources/db/migration/V*.sql` 37개를 **Flyway 버전 순서**(`sort -t_ -k1,1n`)로 적용.
   각 파일을 `BEGIN;`/`COMMIT;`로 감싸 Flyway의 파일당 1트랜잭션 의미를 재현하고,
   `executeInTransaction=false`인 `V29__log_entry_year_month_index.sql`만 비트랜잭션으로 적용.
   적용 실패 0건.
3. `pg_dump --schema-only --no-owner --no-privileges --no-comments`
4. 비결정 라인 제거: `-- Dumped …`, `-- PostgreSQL database dump…`, `\restrict`/`\unrestrict`
   (pg_dump가 실행마다 새로 뽑는 난스). **제거 후 2회 연속 덤프가 byte-identical함을 확인.**
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

- `a4-backend-gate.log` — `tools/parity/gate.sh backend` 전체 출력. `BUILD SUCCESSFUL in 23m 9s`.
- `a4-backend-gate-xml-summary.txt` — Gradle test XML 독립 집계.
  571 suites / 4862 tests / failures 0 / errors 0 / skipped 1.
  skipped 1 = `opensamguk.engine.golden.LongSimReplayGateTest`(CLAUDE.md에 기록된 **기존** 백로그
  "long-sim multi-turn (gate dim c)"). 이번 티켓이 만든 skip이 아니다.
- `a4-web-gate.log` — `cd web/game && pnpm typecheck && pnpm test`.
  typecheck 무출력 통과, 54 files / 287 tests 전부 pass. `__tests__/v2-lab-route.test.tsx` 16건 포함.

---

## UNKNOWN / 미측정

- **`a4-web-gate.log` 테스트 개수 변동** — 3회 실행 중 1회차 284, 2·3회차 287(파일 수는 3회 모두 54,
  전 회차 전부 pass). 3건 차이의 원인 **UNKNOWN**. artifact에는 2회차(287) 출력을 보존했다.
- **라이브 Flyway 적용 스키마 dump** — A1은 psql 재현본이며 `flyway_schema_history` 미포함.
  실 Flyway 부팅 dump는 미생성.
- **v2 마이그레이션 적용 후 스키마** — `db/migration_v2/`는 README뿐이라 적용할 것이 없다.
  버전 번호 정책은 OPENSAM-150(R1) 소관으로 계획서 §S1이 미확정 처리했다.
