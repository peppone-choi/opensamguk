# Agent Handoff


## Current handoff (2026-08-23) — OPENSAM-226 W1-B remediation interrupted; resume from RED

- Reason: 사용자가 진행 중인 구현을 중단하고 핸드오프 후 대기를 요청했다. 실행 중이던
  `w1b-pack-implementer`를 interrupt했고, commit/push/merge/deploy/delete는 수행하지 않았다.
- Completed prerequisite: W0 전체와 W1-A는 독립 검토 `cleared`. W1-A artifact SHA는
  contract `fcc6a110…`, registry `fd2e7a82…`, corridor candidates `6b086297…`,
  external candidates `dd7359de…`다.
- Current W1-B files: `tools/map/external_world_*.py`, 두 external-world 테스트,
  `data/curated/han/external-world-{pack-index,legacy-adjudications}-v1.json`, 다섯 pack JSON.
  모두 uncommitted/untracked 작업이며 다른 dirty 변경과 함께 보존해야 한다.
- Initial snapshot only: 5 packs, entities 53/APPROVED 42, claims 45, scenario states
  1,643/ACTIVE 0, ledger 65 (`SUPERSEDE 27`, `REJECT 1`, pack rows 37), approved corridor 0.
  최초 구현에서 14/14와 `--check`가 green이었지만 독립 검토 `fix-required` 뒤 현재
  합격 증거로 폐기했다.
- Blocking review findings:
  1. pack SHA 재계산과 함께 claim value/sourceRef/subject/period를 위조해도 통과한다.
  2. entity `sourceClaimRefs`와 claim `subjectEntityKey`의 양방향 exact 결합이 없다.
  3. APPROVED 42개인데 ACTIVE 0을 생성기/validator가 강제한다.
  4. legacy APPROVE 37 중 11은 비승인 결과이며 summary 37이 하드코딩됐다.
  5. 모든 사료를 `OFFICIAL_HISTORY`로 일괄 분류해 후대 지리지까지 승격한다.
- Interrupted edit state: `test_external_world_source_validation.py`에 claim/source swap,
  완전 위조, 교차주체/orphan, period sentinel/source grade mutation 테스트가 추가됐고,
  `test_external_world_contract_validation.py`에 비승인 결과 APPROVE와 derived summary
  mutation 테스트가 추가됐다. 구현 파일과 JSON은 기존 의미를 유지한다. 새 테스트는
  interrupt 뒤 실행하지 않았으므로 RED 여부도 현재 exact tree에서는 미관측이다.
- Historical corrections already decided from local corpus:
  - 扶南: 기존 245 제거. `sgz-47.txt` 107행 赤烏元年(238) + 119행 六年 사건을
    결합한 243년 exact chronology.
  - 夷洲: 77행 黃龍元年(229) + 85행 二年 사건을 두 span으로 결합한 230년.
  - 帶方: `sgz-30.txt` 74행 建安中, 196~220 RANGE; 204 하드코딩 금지.
  - 林邑: `sui-082.txt` 9행 漢末의 원문 정밀도 보존.
  - 流求: 후대 alias-only, 모든 현재 시나리오 INACTIVE.
- Resume sequence:
  1. `.ai/task.md`, `.ai/decisions.md`, `docs/agent/project-overview.md`, 이 문서를 읽는다.
  2. 현재 새 mutation tests를 먼저 실행해 RED를 관측한다. 실패하지 않는 mutation은
     테스트/validator 경계를 다시 확인하되 테스트를 약화하지 않는다.
  3. claim별 reviewed binding에 exact citation spans/attested form, semantic value,
     subject, period precision을 분리하고 source raw text/SHA/line과 결합한다.
  4. entity↔claim 양방향 exact 검증, reviewed lifecycle→31 scenario state 파생,
     실제 APPROVED 결과만 legacy APPROVE, 자료별 evidence/source proximity를 구현한다.
  5. 다섯 pack과 index/ledger를 결정적으로 재생성하고 focused tests, actual `--check`,
     Ruff, basedpyright, py_compile, no-excuse를 실행한다.
  6. 같은 독립 reviewer로 최초 mutation 묶음을 재검증하고 `cleared` 후에만 W1-B
     Agent OS review/strict를 기록한다. 그 전 W1-C 금지.
- Do not repeat: all-INACTIVE를 runtime 비활성 경계로 정당화하지 않는다. broad source
  range 존재만으로 claim 의미를 승인하지 않는다. claim 이름별 하드코딩이나 count-only
  guard로 위조를 가리지 않는다. 扶南 245, 帶方 204, 林邑 192를 근거 없이 재사용하지 않는다.
- Verification at pause: 새 remediation tests와 현재 exact tree의 모든 검증은 미실행.
  체크포인트의 read-only `sed`/`rg`는 exit 0이었으나 비특정 fablize wrapper 경고가
  반복됐고 제품 실패와 분리해 기록했다.

## Current handoff (2026-08-23) — OPENSAM-206~220 integration wave; OPENSAM-218 policy gate

- Current task authority is `.ai/task.md` section `OPENSAM-206~220 통합·검증·배포 (활성 계약)` and the active integration worktree named there.
- OPENSAM-218 is the bounded policy lane in PR #499. ADR-LITE-042 retires PHP grand-truth, PHP-wins, draw-for-draw, byte-log, and golden-first product gates while retaining truthful evidence, frozen existing tests, deterministic replay, one-daemon-write, flush-delta, and insertion-order rules.
- This handoff records policy/guard verification only. The integration wave owns merge, deploy, production observation, Jira transition, and cleanup; none is claimed here.
- Durable OPENSAM-218 result and risk evidence is recorded in `reports/opensamguk/tasks/2026-08-23-op218-parity-adr.md` in the metarepo.

---

## Historical handoff (2026-08-09) — OPENSAM-43 V2-0B terminal structural review cleared; full verifier green

- HEAD `8abb47a1` structural dirty tree is terminally `cleared` with no findings:
  combined fingerprint `0734d9d5625b70fb6a92ea12c6e5717302b1b689aadcc46a4f17fcbf06f28ac3`
  (tracked `023225…06f4`; untracked fixture `898063…dd0`).
- Runtime now compares a PostgreSQL `pg_class` OID baseline with post-v2 catalog
  state and includes duplicate-key/FK fixes. Focused convention 17, mutation 4,
  V2Both 2, and infra catalog 11 are all green.
- The authorized healthy-Docker isolated `scripts/agent/verify-changes.sh --run`
  rerun passed: exit 0, `BUILD SUCCESSFUL in 23m 46s` / 29 tasks; common 232 +
  logic 3,173 + infra 236 + game-api 468 + game-engine 822 = 4,931 tests /
  failures 0 / errors 0 / engine skipped 1. Verifier strict was 46 changed /
  Errors 0 / Warnings 0 / findings 0; log
  `/tmp/op43-catalog-diff-final-os-verify-rerun.log` SHA-256 is
  `a95386e902908c199f12d86cb776e06d97ead25eef71e9dc3647b0e3e671e31e`.
- The preceding first run is historical infrastructure-only: transient Docker
  HTTP 500 before game-api app assertions; log
  `/tmp/op43-catalog-diff-final-os-verify.log` SHA-256 is
  `706131db0b7c24a2e57d4c7875031b195240b2e565ca2b798f54098bada6aadc`.
- The prior P2 reports, immutable clearance, focused 6 + 1/16 + 2, and 4,916
  verifier are historical pre-current-structural-tree evidence only.
- Next: commit/push and observe remote CI for that exact SHA. The
  PR-conversation review counter remains 0/3; no merge or deployment is implied.
- The earlier `5c93a23653012a0e557b720f701374ea2fe2c86ea5cebf718856d51933e17360`
  clearance and 4,914-test verifier are historical evidence only; they do not
  replace the current exact structural-tree clearance or 4,931 full verifier.
- No commit, push, merge, deployment, cutover, production observation, secret
  access, data deletion, legacy/golden write, or test weakening occurred in
  this documentation handoff.

## Durable references

- Current task/state: `.ai/task.md`, `.ai/current-state.md`
- Controlling review: `docs/superpowers/reviews/2026-08-09-opensam-43-v2-0b-runtime-review.md`
- Approved scope: `docs/superpowers/plans/2026-08-09-opensam-43-v2-0b-runtime-contract-plan.md`
- Long-form history: `docs/superpowers/SESSION_HANDOFF.md`
