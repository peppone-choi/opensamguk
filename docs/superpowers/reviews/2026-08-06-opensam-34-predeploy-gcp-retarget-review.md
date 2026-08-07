# Review: predeploy Go 체크 gcp-prod retarget + D4-35 마이그레이션 스캔 결함 (PR #364)

Scope: `tools/ops/predeploy_go_check.sh`, `tools/ops/predeploy_go_check_contract_test.sh`, `.github/workflows/predeploy-go-check.yml` — OPENSAM-34 D4-31~35 배포 전 Go/No-Go grader의 러너 라벨 retarget과 checkout 마이그레이션 스캔 경로
Verdict: quarantined-with-proof
Proof: `KOTLIN_MIGRATIONS_DIR` 가드 부재로 인한 false GO를 격리 재현(37 vs 38)하고 `predeploy_go_check.sh:155-159` 한 줄 가드로 폐쇄했으며, 수정 후 `bash -n` 양쪽 PASS와 hermetic 계약 테스트 `PASS` EXIT=0을 재측정했다. 격리 사유는 아래 "이 리뷰가 만족하지 못한 요건"이며, 머지 전 독립 프로바이더 재검토가 남아 있다.

## 이 리뷰가 만족하지 못한 요건 (먼저 읽을 것)

**이 문서는 독립 에이전트가 쓰지 않았다.** `CLAUDE.md`의 cross-agent critique 규칙은 비평자가 작성자와 분리된 에이전트/프로바이더일 것을 요구한다. 이 세션에서 독립 비평을 두 차례 위임했고 —
`oh-my-claudecode:critic`(Opus)와 `deep-reasoner`(Opus) — **두 에이전트 모두 각각 두 번씩 보고 없이 idle 처리됐다.** 재요청에도 리포트 본문이 돌아오지 않았다. 서로 다른 계열에서 동일 증상이 반복된 것으로 보아 이 세션의 서브에이전트 최종 보고 전달 경로 문제이며, 리뷰 내용의 문제가 아니다.

사용자는 세션 중 명시적으로 "제 검증으로 아티팩트 작성"을 선택했다. 따라서 아래 내용은 **이 세션이 직접 실행한 검증**이고, 규칙이 요구하는 독립 비평은 **아직 수행되지 않았다.** 머지 전에 Codex 등 다른 프로바이더로 이 문서와 diff를 다시 공격해야 한다. 그 재검토 전까지 이 PR을 머지 가능으로 읽지 말 것.

## 발견하고 폐쇄한 결함 (major, 재현 완료)

**`predeploy_go_check.sh:81,145,154` — `KOTLIN_MIGRATIONS_DIR`에 존재 가드가 없어 false GO가 가능했다.**

`MIGRATIONS_DIR`는 `:154`에서 `[[ -d ]] || no_go`로 막혀 있으나 `KOTLIN_MIGRATIONS_DIR`(`:145`)에는 대응 가드가 없었다. `:80` `shopt -s nullglob` 때문에 경로가 없거나 stale하면 glob이 **에러 없이 빈 값으로 확장**되고, 스캔이 `.sql`-only 최댓값으로 조용히 퇴화한다.

격리 재현(함수만 떼어내 `KOTLIN_MIGRATIONS_DIR`만 존재하지 않는 경로로 지정):

```
correct kotlin path : 38
stale kotlin path   : 37   <-- no error, no NO-GO
```

실패 시나리오: grader가 `infra/src/main/kotlin/db/migration`이 없거나 리네임된 체크아웃에서 실행되고, 배포 대상 이미지에는 `V38__rtk14_npc_lifecycle_repair.kt`가 **미적용** 상태로 실려 있다 → checkout 기대값 37, DB `MAX(version::integer)` 37 → `:219`가 `37 == 37`로 통과 → **GO**. 미적용 마이그레이션을 안고 배포가 진행된다. 이는 이 PR이 닫았다고 주장한 바로 그 false GO다.

### 이전 세션의 철회 근거가 사실과 달랐다

`.ai/current-state.md`와 `.ai/task.md`는 최초안의 `[[ -d "$KOTLIN_MIGRATIONS_DIR" ]]` 가드를 "디렉터리 부재는 Kotlin 마이그레이션 부재를 뜻하므로 37 기대가 옳고, 이 가드는 정상 스택에 영구 false NO-GO만 만든다"는 이유로 철회했다고 기록한다. 그 전제가 틀렸다 — 해당 디렉터리는 **git 추적 대상**이다:

```
$ git ls-files infra/src/main/kotlin/db/migration
infra/src/main/kotlin/db/migration/V26__npc_lifecycle_phase_units.kt
infra/src/main/kotlin/db/migration/V38__rtk14_npc_lifecycle_repair.kt
```

정상 체크아웃에는 항상 존재하므로 영구 false NO-GO는 발생할 수 없다. 이 가드는 **실제로 뭔가 잘못됐을 때만** 발동한다. 철회는 되돌렸다(`:155-159`, 사유 주석 포함). 기존 `(( highest > 0 ))`(`:91`)는 "숫자 마이그레이션 0개"만 덮을 뿐 "`.sql`은 있고 Kotlin 경로만 사라진" 이 케이스를 덮지 못한다.

## 반증을 시도했으나 실패한 주장 (= 정상 확인)

| 주장 | 검증 | 결과 |
| --- | --- | --- |
| `.sql`만 37 / Kotlin 포함 38 | 실제 파일에서 버전 추출·정렬 | 사실. `.sql` 파일은 36개이고 V26이 `.kt`라 번호가 빔 |
| V38이 클래스명에서 버전 38을 유도 | `grep -rn getVersion infra/src/main/kotlin/db/migration/` → none | 오버라이드 없음, 유효 |
| `:88` `(( )) && assign`이 `set -e`로 죽지 않는다 | `bash -euo pipefail -c 'h=5; for v in 3 9; do (( 10#$v > h )) && h=$(( 10#$v )); done; echo survived'` → `survived h=9` | 안전. 함수 마지막 명령이 아님 |
| 계약 테스트가 동어반복이 됐다 | `:25-32`는 `sed` zero-strip + `sort -n`으로 파생하고 grader는 `10#`를 쓴다. grader가 `.sql`-only로 회귀하면 stub DB 38 vs grader 37 → NO-GO → `assert_success` 실패 | **동어반복 아님.** 독립 구현 대조다 |
| `:231`의 `IFS='|' read` 3변수가 인덱스 정의 안의 `|`에 깨진다 | 마지막 변수가 나머지를 모두 받는 `read` 시맨틱 | 깨지지 않음 |
| `:108` df 파싱이 fail-open | 필드 부족 시 빈 값 → `:109` 정규식 실패 → `no_go` | fail-closed |
| 운영자 입력으로 SQL 인젝션 | `$EXPECTED_WORLD_ID`는 `^[1-9][0-9]*$`(`:136`), `$EXPECTED_SCENARIO_CODE`는 `^scenario_(0\|[1-9][0-9]*)$`(`:134`)로 검증 후에만 사용. `psql_read`(`:68-73`)는 `sh -ceu '... "$1"' sh "$sql"`로 위치인자 전달이며 셸 문자열 보간이 아님 | 도달 경로 없음 |
| `no_go`가 `$( )` 안에서 호출되면 fail-open | `:91`은 서브셸 exit 1 → `:215` 대입 실패 → `set -e`로 종료 코드 1 유지, stderr 메시지도 출력됨 | fail-closed (아래 minor 참조) |

## 남은 minor (수정하지 않음, 근거 기록)

- **`:39-50` `safe_env_value`의 반환코드 구분이 호출부에서 소실된다.** 중복 키는 exit 2, 키 부재는 exit 1인데 `:175`·`:176`·`:192` 세 곳 모두 "…key is unavailable" 한 문구로 접는다. fail-closed는 유지되나 중복 키 사고를 "키 없음"으로 오진하게 만든다.
- **`:91`의 fail-closed가 우연에 의존한다.** 명령 치환 안의 `exit`는 서브셸만 끝내고, 바깥이 멈추는 것은 `set -e`가 대입 실패를 잡기 때문이다. `EXPECTED_MIGRATION_VERSION="$(...)"`가 언젠가 `if`/`||` 문맥으로 옮겨지면 조용히 fail-open으로 뒤집힌다.
- **`:234-235` 인덱스 정의 동등 비교가 PostgreSQL 출력 포맷에 민감하다.** `pg_get_indexdef`의 opclass·tablespace·포맷 변화가 정상 인덱스를 NO-GO로 만들 수 있다. 방향은 안전(fail-closed)하지만 운영자를 헛되이 막을 수 있다.
- **`.ai` 문서의 `10#` 관련 과장.** 현재 저장소에 zero-padded(`V0*`) 마이그레이션은 하나도 없다. `10#` 수정은 활성 버그가 아니라 잠재 결함 방어이며 "실측 확인"이라는 서술은 과장이다. 같은 커밋에서 문서를 정정한다.

## 이 변경이 닫지 않는 것 (known limits)

- **D4-33은 "프로덕션 러너"를 증명하지 않는다.** `:156-158`은 `GITHUB_ACTIONS`/`RUNNER_OS`/`RUNNER_ARCH`만 본다. 아무 self-hosted Linux/X64 러너에서 실행해도 통과하며, gcp-prod 귀속은 워크플로의 `runs-on`만이 묶고 그것은 계약 테스트의 문자열 grep(`:328`)으로만 고정된다. 이번 변경의 주제가 러너 retarget인 만큼 명시해 둔다.
- **새 Kotlin 디렉터리 가드를 덮는 실행 테스트가 없다.** 계약 테스트는 실제 `REPO_ROOT`를 쓰므로 디렉터리를 없앤 케이스를 만들 수 없다. 두 경로를 env로 오버라이드 가능하게 만들면 테스트할 수 있으나, 프로덕션 grader에 새 주입면을 여는 대가가 이득보다 크다고 판단해 하지 않았다. 가드 회귀는 현재 리뷰로만 막힌다.
- **계약 테스트는 grader와 테스트의 파생 로직을 함께 바꾸면 잡지 못한다.** 미러 대조의 구조적 한계다.
- **D4-31~35의 실제 프로덕션 관측은 여전히 미실행이다.** 이 리뷰는 로컬 계약 결론이며 Go 판정이 아니다. 워크플로 dispatch에는 별도 명시 승인이 필요하다.
- **`.java` 마이그레이션과 repeatable `R__`은 스캔 대상이 아니다.** 현재 저장소에 둘 다 없음을 확인했으나(`find infra/src/main -name 'V*__*.java'`, `-name 'R__*'` 모두 공집합) 장래에 생기면 같은 종류의 조용한 누락이 재발한다.

## 증거 로그

```
$ ls infra/src/main/resources/db/migration/V*__*.sql | wc -l
36
$ ls infra/src/main/resources/db/migration/V*__*.sql | sed 's/.*\/V\([0-9]*\)__.*/\1/' | sort -n | tail -1
37
$ { ls .../resources/db/migration/V*__*.sql; ls .../kotlin/db/migration/V*__*.kt; } | sed ... | sort -n | tail -1
38
$ grep -rn "getVersion" infra/src/main/kotlin/db/migration/
none
$ git ls-files infra/src/main/kotlin/db/migration
V26__npc_lifecycle_phase_units.kt, V38__rtk14_npc_lifecycle_repair.kt
$ bash -euo pipefail -c 'h=5; for v in 3 9; do (( 10#$v > h )) && h=$(( 10#$v )); done; echo "survived h=$h"'
survived h=9
```

가드 복구 후 재측정:

```
$ bash -n tools/ops/predeploy_go_check.sh                  → PASS
$ bash -n tools/ops/predeploy_go_check_contract_test.sh     → PASS
$ bash tools/ops/predeploy_go_check_contract_test.sh
PASS: predeploy-go-check hermetic contract
EXIT=0
```

이 리뷰 과정에서 배포·워크플로 dispatch·프로덕션 접근은 일어나지 않았고, `.env*`·토큰·시크릿을 읽거나 출력하지 않았다.
