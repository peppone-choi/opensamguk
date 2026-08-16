# OPENSAM-35 (0A-g) 기준선 artifact MANIFEST

계획 §1 M3 규약: `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/` 아래 4종 + 본 MANIFEST에 sha256.

- 생성 시각: 2026-08-08
- 리포 상태: A1~A3과 web gate는 historical baseline artifact다. A4 backend log는 PR #370 Round 1
  current dirty-tree의 Java 21 `--rerun-tasks` full gate 한 번(no retry)으로 교체했다:
  601 suites / 5050 tests / failures 0 / errors 0 / skipped 1, SHA256
  `a35ea5cf8352e2fe518daa32dbe95343f92bf62c95dc41a3673e924aa9fcaad1`.
- 완료된 review/CI evidence는 구분해 보존한다: independent dirty-tree review는 no findings로
  `cleared`(fingerprint `3c1b357c…`), local immutable-SHA review는
  `54ead4e70cf5fa7c822bc7fef11a8c42f09eded6`에서 완료됐다. GitHub PR head `70492bcc`에서는
  `agent-system`·`jvm`·`web (gateway)`·`web (game)` jobs가 SUCCESS였고, `agent-system`에는 original
  `Verify v2 sandbox compose contract` step이 포함됐다. 이 green CI는 final-8 dirty remediation 전 evidence라
  현재 permission/active-matcher/documentation changes의 remote CI를 대체하지 않는다.
- Round 3의 두 P2는 prior dirty-tree re-review에서 `cleared`된 **historical** disposition이다. 현재 controlling
  review state는 terminal independent final-8 dirty-tree re-review의 `cleared`(no findings)다: 문서 증거(A4
  header/hash, manifest verifier, S1/S3b/U12, PHP disposition)와 source(CI permission + active-invocation check)의
  eight dispositions가 모두 resolved됐다. Compose `ASSET_PREFIX=/game`, rendered-Compose contract red-before/green-after,
  local production-mode build의 62개 `/game/_next/` output, 그리고 local CI wiring validation은 implementation evidence다.
  Remote CI for an exact final-remediation commit is still unobserved. CodeRabbit Round 2는 rate-limited request로 review result가 없으며,
  PR review mentions=3은 세 submitted results—CodeRabbit Round 1(23), Codex Round 3(2), final CodeRabbit
  incremental(8)—을 뜻한다. local exact-SHA/dirty-tree reviews는 별도다. The terminal final-8 review clears the
  reviewed dirty tree only; it does not stand in for remote exact-commit CI, merge, release, or deploy.
- 브랜치는 `origin/main=b847c351`에서 OPENSAM-35 변경을 단일 커밋으로 재구성했으나, PR #370은
  open이고 merge/release/deploy는 미수행이다.

## 파일 sha256

| 파일 | sha256 |
|---|---|
| `a1-v1-schema-dump.sql` | `de16ba5bd3c5f531021e65b9432c761b3a296dd6cb3bcf1ff23e81259be2ce50` |
| `a1-v1-flyway-migration-sha256.txt` | `6888b07802d988a1e6d64fe974d6056f053e33022091ac60da94ef69d964bceb` |
| `a2-scenario-seed-sha256.txt` | `f42d3a4f935be3a63de1524f146819c7b9bc1160c0b3aa255f7eb57a32bbbb67` |
| `a3-php-golden-inventory-sha256.txt` | `229ee5cdb3f2c4e612d4593bf57eb1af83983cbb51189dfe990eb1a8fe15f233` |
| `a4-backend-gate.log` | `a35ea5cf8352e2fe518daa32dbe95343f92bf62c95dc41a3673e924aa9fcaad1` |
| `a4-backend-gate-xml-summary.txt` | `7d497d7423bc861e41e1bbb8a6418d66585d3e69d0e53b908653449a1f845e82` |
| `a4-web-gate.log` | `d803e60c5410b69409e98ed20c21a7b8661d313da8e17e91072252422fd3ca32` |

재검증:

```bash
set -euo pipefail
baseline=docs/loops/opensam-35-v2-0a-2026-08-08/baseline
(
  cd "$baseline"
  shasum -a 256 -c <(
    awk -F'`' '/^\| `a[1-4]-/ { print $4 "  " $2 }' MANIFEST.md
  )
)
```

이 명령은 table의 7개 artifact checksum을 `shasum -c`에 전달하므로 한 파일이라도 달라지면 non-zero로
실패한다. 단순 출력/정렬이 아니라 fail-closed verification이다.

안전한 mutation proof(정본 artifact에는 write/delete 없음; fresh temp copy는 cleanup 명령 없이 남긴다):

```bash
set -euo pipefail
baseline=docs/loops/opensam-35-v2-0a-2026-08-08/baseline
proof_dir=$(mktemp -d "${TMPDIR:-/tmp}/op35-manifest-mutation.XXXXXX")
probe="$proof_dir/a4-backend-gate-xml-summary.txt"
cp "$baseline/a4-backend-gate-xml-summary.txt" "$probe"
printf '\n# deliberate mutation proof\n' >> "$probe"
expected=$(awk -F'`' '$2 == "a4-backend-gate-xml-summary.txt" { print $4 }' "$baseline/MANIFEST.md")
if printf '%s  %s\n' "$expected" "$probe" | shasum -a 256 -c -; then
  printf 'ERROR: mutation unexpectedly verified\n' >&2
  exit 1
fi
printf 'PASS: copied-file mutation rejected; scratch retained at %s\n' "$proof_dir"
```

전 파일 100KB 미만이므로 M3의 "대용량 덤프는 gitignore" 예외는 적용하지 않는다. 전부 커밋 대상.

---

## A1. v1 schema dump — **생성됨 (실측)**

`a1-v1-schema-dump.sql` — 45 테이블 / 49 인덱스 / 3204행.

생성 방법 (재현 가능, 지어낸 값 0):

1. `postgres:16-alpine`를 비표준 포트 55435로 일회성 기동 (컨테이너명 `opensam35-s6-v1schema`)
2. `infra/src/main/resources/db/migration/`의 **SQL 36개**를 **Flyway 버전 순서**(`sort -t_ -k1,1n`)로 적용.
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

`a1-v1-flyway-migration-sha256.txt` — 마이그레이션 inventory **37개 파일**(SQL **36개** +
`V29__log_entry_year_month_index.sql.conf` **1개**)의 파일별 sha256. `.conf`는 Flyway가 적용하는
SQL이 아니라 V29 metadata이며, schema dump 입력 SQL 수와 혼동하지 않는다.

## A2. seed hash — **생성됨 (실측)**

`a2-scenario-seed-sha256.txt` — `data/extracted/scenario/`의 tracked 시드 소스 **82개**
(`scenario_*.json` 81 + `_meta.json`) 파일별 sha256.

**각주 (2026-08-17):** 이후 옵션 IP 초상 세트 퍼지로 그 세트를 참조하던 시나리오 16개를 삭제해
트리 실제값은 **66개**(`scenario_*.json` 65 + `_meta.json`)가 됐다. `a2-scenario-seed-sha256.txt`
본문은 **그대로 둔다** — 위 sha256 표에 그 파일의 해시(`f42d3a4f…bbb67`)가 고정돼 있어 편집하면 이 MANIFEST가 깨지고,
기준선은 "그 시점에 무엇을 관측했는가"의 기록이기 때문이다. 삭제 목록·근거는
`docs/superpowers/research/2026-08-17-asset-license-audit.md` 부록 D 참고.

**한계:** RTK14 5스탯 생성본(`SCENARIO_DIR` 오버라이드용)은 CLAUDE.md 규약상 **git-ignore·미커밋**이라
기준선에 포함하지 않는다. 라이브 DB에 실제 시드된 행의 해시가 아니라 **시드 소스 파일** 해시다.

## A3. PHP golden — **scope/inventory proof; replay claim 없음**

이 artifact는 PHP capture 또는 draw-for-draw replay를 실행하거나 통과시킨 기록이 아니다. 이 0A
isolation/build-only ticket은 T1/parity code를 바꾸지 않았으므로 그러한 replay가 acceptance event가
아니다. 다음 세 항목은 오직 **scope**를 보인다:

1. **T1 diff inventory** — `logic/src/main/kotlin/`·`common/src/main/kotlin/`·
   `logic/src/test/resources/golden/`에 수정/삭제가 없다는 canonical merge-base glob measurement.
2. **golden inventory/head object** — historical golden tree object와 273개 파일별 sha256 inventory가
   보존돼 있고 working tree golden path에 변경이 없다는 비교 근거.
3. **0A dependency inventory** — isolation gate source에 `opensamguk.logic`/`opensamguk.common` import,
   `RandUtil`, `PhpRound`, `LiteHashDrbg`, `ConvertLog`, `Josa`가 없다는 static scope check.

따라서 A3은 “replay passed”가 아니며, A4 backend gate나 이후 T1/parity ticket의 PHP capture/replay를
대체하지 않는다. 후속 ticket이 T1/parity behavior를 바꾸면 PHP oracle capture/replay가 별도로 필수다.

`a3-php-golden-inventory-sha256.txt` — 골든 파일 **273개**의 파일별 sha256. 향후 회귀 비교용 고정값.

## A4. backend / web gate — **current Round 1 backend evidence; historical web artifact**

- `a4-backend-gate.log` — current dirty-tree에서 Java 21로 실행한
  `tools/parity/gate.sh backend` 전체 출력. 이 gate는 `--rerun-tasks` 여섯 test root를 실행했고,
  **one run / no retry**로 `BUILD SUCCESSFUL in 12m 35s`, 35 actionable tasks executed,
  XML 601 suites / 5050 tests / failures 0 / errors 0 / skipped 1을 기록했다. 전체 로그 SHA256은
  `a35ea5cf8352e2fe518daa32dbe95343f92bf62c95dc41a3673e924aa9fcaad1`다.
- `a4-backend-gate-xml-summary.txt` — 위 로그와 같은 run의 Gradle test XML 독립 집계.
  common 38/225, logic 277/3173, infra 55/226, game-engine 132/799 (skip 1), game-api 72/468,
  gateway-api 27/159이며 failures/errors는 모두 0이다. skipped 1 =
  `opensamguk.engine.golden.LongSimReplayGateTest`(CLAUDE.md에 기록된 **기존** 백로그
  "long-sim multi-turn (gate dim c)"). 이번 티켓이 만든 skip이 아니다.
- `a4-web-gate.log` — historical `cd web/game && pnpm typecheck && pnpm test` result:
  typecheck 무출력 통과, 54 files / 288 tests 전부 pass. `__tests__/v2-lab-route.test.tsx` 17건과
  `middleware.test.ts` 8건을 포함한다.

앞선 599/5023 non-forced backend record와 286/1,652 four-root subset은 historical evidence로만 남긴다.
그 verifier의 frontend dependency absence
(`tsc: command not found`)는 historical failure다. Later direct-pnpm frontend typecheck is green and Vitest
JSON reports 132 suites / 288 tests / 0 failures. Current backend evidence는 source remediation을 관측하는
근거이나, remote exact-commit CI 또는 merge/release/deploy approval을 대체하지 않는다.

---

## UNKNOWN / 미측정

- **과거 `a4-web-gate.log` 테스트 개수 변동** — remediation 전 3회 실행은 284/287/287로
  달랐고 원인은 UNKNOWN이었다. 현재 artifact는 remediation 뒤 fresh 288건 출력이며 전부 pass다.
- **라이브 Flyway 적용 스키마 dump** — A1은 psql 재현본이며 `flyway_schema_history` 미포함.
  실 Flyway 부팅 dump는 미생성.
- **v2 마이그레이션 적용 후 스키마** — `db/migration_v2/`는 README뿐이라 적용할 것이 없다.
  버전 번호 정책은 OPENSAM-150(R1) 소관으로 계획서 §S1이 미확정 처리했다.
