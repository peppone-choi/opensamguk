# Review: OPENSAM-35 — V2-0A production 격리 게이트 선설치

Scope: `op-35-v2-0a`의 merge-base `fb90eac1` 이후 전체 변경 — `app/`, `infra/`, `tools/`, `web/`의 v2 route/configuration/content/Flyway/compose 격리, production-context bean gate, naming guard, 표준 backend gate, 기준선 artifact와 Agent OS 상태
Verdict: cleared

## 독립 최종 리뷰 1차 (2026-08-08)

fresh `fable-deep-reasoner`가 구현자와 분리된 read-only 적대적 리뷰를 수행했다. 제품 경로에서는
v2 route hard 404, Flyway 형제 location, compose fail-closed env/secret, T1/T2/config/C1 격리가
유효함을 확인했다. 그러나 아래 항목 때문에 merge 후보가 아니다.

### Blocker

1. ~~**v2 leaf acceptance 충돌.**~~ **해소(사용자 승인, ADR-LITE-029).** `.ai/task.md:35`와 계획 §S5는 v2 leaf 행 존재를 요구했지만,
   계획 §S5 실측은 리포에 실제 v2 콘텐츠가 0건이라 미충족으로 남겼다. OPENSAM-150 스키마를
   이 티켓의 비범위로 둔 계약과 동시에 만족할 수 없었다. leaf를 날조하지 않고 수용 기준을
   v2 전용 probe 2행 + v1 기본 12행 미적재로 정정했으며, 실제 leaf는 OPENSAM-150 필수 기준으로 이관했다.
2. **PR-visible cleared critique 부재.** 기존 GATE-f/GATE-f2는 `docs/loops/**`에 있고 둘 다
   `fix-required`다. 이 문서가 1차 판정을 보존하며, remediation 뒤 fresh 재리뷰가 `cleared`여야 한다.
3. **기준선·상태 stale.** 최초 A4는 gateway-api 합류 전 571/4,862와 web 287이었고 계획·ownership·
   current-state도 `gate.sh` 수정/실행 상태를 반영하지 않았다.

### Fix-required

1. `tools/parity/gate.sh`가 선택된 XML root를 합쳐서만 검사해 한 모듈의 XML이 0개여도 다른
   모듈 XML로 통과한다. **root별 최소 1개**를 강제해야 한다.
2. `V2NamingConventionGuardTest`가 여섯 모듈의 raw source를 읽지만 game-engine `test` task input은
   이를 선언하지 않아 타 모듈 위반이 생겨도 `UP-TO-DATE` false-green이 가능하다.
3. gateway production-context gate의 positive control이 `opensamguk.gateway` 타입 접두 전체라
   `@Import(ProfileIconSecureStorageTestConfiguration)`만으로도 충족될 수 있다. 특정 production-scanned
   빈을 단언해야 한다.
4. 기존 `18b8bd95` 트레일러는 `Claude Opus 5`라 `CLAUDE.md`의 정확한 `Claude Opus 4.8` 규약과
   다르다. 이미 푸시된 이력을 강제 수정하지 않고 최신 main 기반 최종 단일 커밋으로 재구성한다.
5. ADR-LITE-026의 PR 대화 리뷰 3라운드는 PR 생성 뒤 별도로 수행해야 한다.

### 후속 경계

- 현재 v2 persistence 위반은 0건이다. 다만 `DaemonWriteGuard`가 `engine.v2`를 제외하므로 실제
  persistence를 여는 OPENSAM-150 전에 one-daemon-write guard를 확장해야 한다.
- green gate는 브랜치 HEAD 기준이므로 최종 PR merge ref/current main 통합에서도 재검증한다.

## 1차 검증 증거

- backend remediation 전 fresh gate: `BUILD SUCCESSFUL in 8m 46s`, 599 suites / 5,023 tests /
  failures 0 / errors 0 / skipped 1. gateway-api 27 suites / 159 tests 포함.
- web/game: 설치된 `pnpm` 직접 실행으로 typecheck PASS, 54 files / 288 tests PASS.
- baseline MANIFEST의 7개 artifact sha256은 현재 파일과 전부 일치.
- T1, T2, 설정 리소스, C1 diff는 merge-base 기준 전부 빈 출력.

## Remediation

- v2 leaf 계약: 사용자 승인으로 ADR-LITE-029에 정정. probe 2행 + v1 기본 12행 0을 0A 판정으로,
  실제 v2 leaf는 OPENSAM-150 필수 수용 기준으로 이관했다.
- XML root 누락: root별 최소 1 XML fail-closed. 5-root 변이는 exit 1로 전환되고 6-root는 exit 0.
- naming guard input: 6개 Kotlin root를 named RELATIVE Gradle input으로 등록. gateway source 변이가
  `:app:game-engine:test` 재실행을 유발함을 `--info`로 관측했다.
- gateway positive control: production `AuthController` 정확히 1개로 고정. stereotype 제거 변이 red,
  복원 후 4 context 4/0/0/0.
- stale A4/상태/계획/ownership: fresh 599/5,023 + web 54/288 기준선과 현재 existing-file 범위로 갱신.
- 잘못된 기존 트레일러: 최신 main 기반 새 최종 브랜치에 변경을 squash해 정확한 4.8 트레일러의
  단일 커밋으로 재구성한다.

상세 red/green 원장: `docs/loops/opensam-35-v2-0a-2026-08-08/gate-f3-remediation.md`.

## Fresh re-review

fresh `fable-deep-reasoner`가 remediation과 문서 동기화가 끝난 exact working tree를 다시 검토했다.
최종 판정은 blocker/fix-required/question/nit 모두 0인 `cleared`다.

- 외부 shared gateway에는 고유 `shared-gateway-relay`만 붙고, v2 web/nginx/game 서비스는
  `opensamguk-v2`에만 남아 cross-project Docker DNS alias 충돌을 만들지 않는다.
- relay는 v2 network에서만 `gateway-api` alias를 받고, required unique
  `SHARED_GATEWAY_UPSTREAM`으로 기존 v1 gateway를 프록시한다. self-alias 변이는 render assertion이 거부했다.
- backend A4는 remediation 뒤 exact-tree 재실행(`BUILD SUCCESSFUL in 19m 36s`, 599/5,023,
  failures/errors 0)으로 교체됐고 MANIFEST 해시가 일치한다.
- OPENSAM-35는 격리 foundation이므로 실제 shared-network 동일 계정/JWT/profile smoke는 이 티켓을
  차단하지 않는다. 해당 runtime 수용 기준은 linked [OPENSAM-177](https://pepponechoi-jira.atlassian.net/browse/OPENSAM-177)이 소유한다.
- T1/T2/config/C1 diff는 0이고 `git diff --check`가 통과했다. 최신 main과 변경 경로 overlap 및
  merge-tree conflict marker도 0이다.

잔여 release 절차는 latest-main 단일 커밋 재구성, ADR-LITE-026 PR 대화 리뷰 3라운드,
merge-ref CI다. 이는 리뷰 결함이 아니라 PR 이후 게이트다.
