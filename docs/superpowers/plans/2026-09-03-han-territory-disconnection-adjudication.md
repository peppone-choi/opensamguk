# Han Territory Disconnection Adjudication Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every disconnected commandery piece and every disconnected county piece in the committed Han map a reviewed verdict, so the map contract can tell historical exclaves, islands and enclaves apart from data misassignments and grid defects, without moving a single cell.

**Architecture:** A read-only checker (`tools/map/audit_territory_disconnections.py`) recomputes the 4-connected components of `parentOwner` (commanderies) and of the county grid derived from `owner` in `data/map/han-tiles.json`, and requires the ledger `data/curated/han/territory-disconnection-adjudications-v1.json` to cover each secondary component exactly once. The ledger is an allowlist of *why a gap is allowed to stay*; it is never an input to the map generator. Verdicts that assert history must cite the 史料 index (`shiliao:`) or CHGIS/TGAZ (`chgis:`), verdicts that only describe the grid cite the grid (`map:`), and each verdict carries the IF rule the game applies to it.

**Tech Stack:** Python 3.11+, `unittest`; evidence from `references/sources/shiliao` (read-only index of 後漢書·三國志·晉書·讀史方輿紀要 etc.) and the TGAZ JSON API.

**Adjudication criteria (220 CE basis):**

| Verdict | Meaning | Evidence required | IF rule |
| --- | --- | --- | --- |
| `HISTORICAL_EXCLAVE` | 220년 소속이 맞고 실제 지리상 다른 郡國 縣에 막혀 본체와 떨어져 있었을 개연성이 있다 | `shiliao:`/`chgis:` | `EXCLAVE_KEEP` (보존, 육로 보급 없음) |
| `PARENT_MISASSIGNMENT` | 220년 소속 郡國이 현재 값과 다르다는 사료가 있다 | `shiliao:`/`chgis:` + `proposedParent` | `MISASSIGNMENT_PENDING_PARENT_LEDGER` (별도 부모 원장 PR 로 이동) |
| `GEOMETRY_DEFECT` | 소속은 맞고 실제로는 붙어 있어야 하는데 격자 형상 때문에 끊겼다 | `map:` + `defectNote` | `DEFECT_PRESERVE_PENDING_GEOMETRY_PR` (자동 채움 금지, 형상 PR 로) |
| `WATER_SEPARATED` | 물로만 분리된 조각(육지 이웃 0). 검사기가 인정하는 물은 SEA·LAKE·RIVER 다 | `map:` (검사기가 재계산해 대조) | `WATER_ROUTE_ONLY` |
| `EXTERNAL_POLITY` | 漢 郡縣 체계 밖 정치체의 조각 | `shiliao:`/`chgis:` | `EXTERNAL_POLITY_POLICY`, 그 조각이 섬이면 `WATER_ROUTE_ONLY` |
| `UNKNOWN` | 검색했으나 근거가 없다 | `searched` 목록 | `UNKNOWN_PRESERVE` |

역사를 주장하는 판정(`HISTORICAL_EXCLAVE`·`PARENT_MISASSIGNMENT`·`EXTERNAL_POLITY`)은 `shiliao:`/`chgis:`
중 하나를 인용해야 하고, 격자를 주장하는 판정(`GEOMETRY_DEFECT`·`WATER_SEPARATED`)은 `map:` 인용이 있어야
한다. 접두어만 있고 뒤가 빈 인용(`"shiliao:"`)은 다시 찾아볼 수 없으므로 거부된다. `http(s)://` 는 TGAZ
같은 온라인 레코드를 가리킬 때만 쓰는 보조 출처이고, 저장소 파일은 `repo:`, UNKNOWN 의 검색 기록은
`searched:` 를 쓴다. 인용에 든 경로는 저장소 상대여야 한다 — 작성자의 체크아웃 경로(`/Users/…`,
`/home/…`), 세션 스크래치패드(`/tmp/…`, `/private/tmp/…`, `/var/folders/…`), 메타 저장소 상대 경로
(`projects/opensamguk/…`)는 다른 독자가 열 수 없으므로 게이트가 막는다.

각 행의 `review` 블록은 판정이 실제로 공격받았다는 기록이므로 검사된다 — `state` 는 원장이 선언한
`reviewStates` 안에 있어야 하고, `votes` 는 `lens`·`refuted`·`reason` 을 갖춘 항목이 최소 하나 있어야
한다. `{"state": "UPHELD", "votes": []}` 는 아무 렌즈도 돌지 않았다는 뜻이므로 거부된다.

No verdict may move cells, repaint a representative colour, or reparent a county by proximity (`policy.noAutomaticRepair`, `noProximityReparenting`, `noRepresentativeColorFill` are mandatory and checked).

## Global Constraints

- Product map is only `han-world-v2` / `han`. `data/map/han-tiles.json` is not modified by this plan.
- Preserve exactly 1,524 spatial provinces, 1,020 jurisdictions, and 172 commanderies.
- `references/sources/shiliao` is read-only: query it, never edit, commit or push it.
- Every historical judgement cites a passage that can be found again in the index (繁體 query) or a TGAZ `source note`; zero hits are recorded as `UNKNOWN` with the books searched.
- Ledger rows are keyed by `componentKey` (`<unitId>#<rank>`) and pin `memberIds` and `cellCount`; any later geometry change that alters a component makes the gate red until the row is re-reviewed.
- `PARENT_MISASSIGNMENT` rows are findings, not patches. They feed later small PRs through `jurisdiction-commandery-adjudications-v1.json` (the PR #623 path), one commandery group at a time.

---

### Task 1: Failing gate first

**Files:**
- Create: `tools/map/tests/test_territory_disconnection_adjudications.py`
- Create: `tools/map/audit_territory_disconnections.py`

- [x] Synthetic 6x4 fixture with a seat county cut in two by another commandery, an island county, and the resulting county-level split; tests for inventory, coverage, stale rows, cell/member/seat/name drift, water-verdict recomputation, evidence prefixes and their content, the `map:` requirement on grid-only verdicts, the effective-window ordering, the `review` block, `proposedParent`/`defectNote`/`searched` requirements, machine-local paths, IF-rule fit, policy flags, duplicate keys, and no mutation of the map document.
- [x] `ValidationLayerTest` gives every type/enum/identity rule its own negative case, and `CliTest` calls `main()` for the four exit codes the CI step depends on. Both exist because deleting those rules used to leave the suite green.
- [x] Red probe on the committed ledger (`--ledger` on a copy, never the real file): 23 rules each mutated once and each observed to exit 1 with its own message.
- [x] `CommittedDataTest` reads the real map and the real ledger: red while the ledger is missing (`FileNotFoundError` / `assertTrue(exists)`), green only when every component is covered.
- [x] Checker CLI: `--inventory` prints the components, `--check` exits 1 on any coverage/drift/evidence error.

### Task 2: Research every land-neighbour disconnection

- [x] Deterministic inventory: 119 secondary components (76 commandery, 43 county) in 71 units; 53 touch another unit by land, 66 touch only SEA/LAKE (one also OUT_OF_SCOPE).
- [x] Dossier per land-neighbour case: members, 後漢書 郡國志 140 CE parent (repository bindings), TGAZ record (coordinates, present location, dated `source note` parent changes), neighbouring units with shared edge counts.
- [x] Judge → adversarial verify (source / geography / chronology lenses) per component; a verdict refuted by two or more lenses is recorded as contested (`UNKNOWN` unless the refuters agree on a corrected verdict backed by evidence).
- [x] Water-only pieces: judge per commandery whether the piece is an attested island/coastal territory, a strait or lake rasterisation cut, or an external polity; the county-level rows over the same cells inherit the verdict.

### Task 3: Ledger and gate green

**Files:**
- Create: `data/curated/han/territory-disconnection-adjudications-v1.json`
- Modify: `.github/workflows/ci.yml` (contracts job step `Verify Han territory disconnection ledger`)

- [x] One row per secondary component with verdict, confidence, `effectiveFrom`/`effectiveTo`, IF rule, evidence, rationale, review votes; `fragmentLedgerRef` where a province-level `deferred` entry in `province-fragment-adjudications-v1.json` covers the same cells.
- [x] `python3 tools/map/audit_territory_disconnections.py --check` green; break one row (cellCount) and confirm red before trusting it.
- [x] `python3 -m unittest discover -s tools/map/tests -p 'test_*.py'` and the scenario suite green.

### Task 3b: Findings the adversarial review returned, and what was done

An independent six-lens review (gate soundness · ledger integrity · scope · tests · repo
rules, plus a citation-verification pass) returned 20 surviving findings. Everything below
was reproduced before it was acted on.

- 이름은 대조되지 않고 있었다 — `unitNameCh`·`memberNamesCh` 가 `inventory()` 에서 계산되면서도
  비교되지 않아, 행이 맞는 성분에 다른 郡의 이름표를 달고도 통과했다. `NAME_DRIFT` 로 막았고,
  縣 층위 행의 `memberNamesCh` 가 빈 배열이라 비교가 공허하던 것도 프로빈스 이름으로 채웠다.
- `review` 블록 전체가 미검증이었고, 원장이 자기 `reviewStates` 표에 없는 상태 4건을 쓰고 있었다.
- `MAP_EVIDENCE_PREFIX` 는 정의만 되고 아무 데서도 쓰이지 않았다 — GEOMETRY_DEFECT 43행 중 3행이
  `map:` 인용 없이 통과하고 있었다. 규칙을 배선하고 3행의 격자 관측을 새로 계산해 넣었다.
- `--check` 의 종료 코드를 확인하는 테스트가 없었다. 붙이자 저장소 밖 `--ledger` 경로에서
  「ledger missing」 분기가 `relative_to` 예외로 죽는 버그가 드러나 함께 고쳤다.
- seat 분기 테스트가 세 가지 seat 값 모두 같은 답을 내 실제로는 아무것도 구별하지 않고 있었다.
- 원장이 1-space 들여쓰기라 `.editorconfig` 의 2-space 와 어긋났다. 재직렬화했다.

### Task 3c: The citation axis, and the three defects it found

The review's `history` lens stalled six times and never ran, leaving the historical axis
uncovered. It was replaced with a sharded citation-verification pass: every `shiliao:` ref
in a balanced 79-row sample re-queried against the index by one reader, and anything it
flagged re-queried independently by a second. 546 refs graded.

**No fabricated citation.** Zero `NOT_FOUND`. The one `UNRESOLVED` — 後漢書 卷109 京兆尹
「秦内史，武帝改…雒阳西九百五十里」 — was resolved by hand against the corpus: the passage is
there verbatim, in 簡體; the second reader had normalised 内→內 before querying and missed it.

208 refs came back `FOUND_BUT_DOES_NOT_SUPPORT`, every one of them present and quoted
verbatim. Two independent readers had graded each ref *in isolation* against the whole
verdict. That is not how the rows are built — and the ledger never said so. Three real
defects came out of it:

- **The ledger did not say how to weigh its own evidence.** `evidenceConventions` now states
  that a row's justification is the union of its refs, and that EXTERNAL_POLITY /
  WATER_SEPARATED rows carry deliberate liveness probes (a neighbouring entry that *must*
  hit) to back a 「그 표기로 0건」 claim — 于山國's zero is propped up by a 州胡 citation that
  attests nothing about 于山國.
- **14 rows glued two opposing arguments into one `rationale`.** On corrected rows the
  withdrawn original sat above the verdict that replaced it; on upheld rows the rejected
  challenge sat below the adjudication that survived. PARENT-0169#1 read 「사료 근거는
  없으므로 PARENT_MISASSIGNMENT 로 보지 않는다」 above a `PARENT_MISASSIGNMENT` verdict, and
  both citation readers scored its 15 refs against the argument the row had dropped. The
  losing argument now lives in `overruledArgument`; `rationale` holds one position. The gate
  rejects the `[반박]` seam inside `rationale`.
- **Four `review.state` values contradicted the votes beside them.** Two
  `TIEBREAK_RESOLVED` rows had no refuter at all (→ `UPHELD`), `PARENT-0168#1` said two
  refuters with three recorded (→ `CORRECTED_BY_3_REFUTERS`), and `42277#1` was labelled
  `INHERITED` while carrying its own votes, two of which overturned it (→
  `CORRECTED_BY_2_REFUTERS`). The gate now checks the state against the tally.

One substantive historical challenge also came out of it and is answered in the ledger:
讀史方輿紀要 卷002 dates 高涼郡 to 桓帝, while 宋書 卷038 says 「漢獻帝建安二十三年〔續漢書
郡國志는 二十五年〕，吳分立，治思平縣」. Either date sits at or before the 220 reference year, and
neither changes the row: no source puts 臨允 in 高涼郡's county list (宋書 領縣七 = 思平·莫陽·
平定·安寧·羅州·西鞏·禽鄉; 晉書 統縣三 = 安寧·高涼·思平), while 後漢書 卷113 郡國志 lists 临元
(=臨允) among 合浦郡's 五城 and 宋書 says 「臨允令，漢舊縣，屬合浦」.

### Task 4: What this ledger is deliberately not wired into

- It is **not** a generator input, so it is not registered in `han_tiles_protected_orchestrator.py`'s role table. The generator never reads it, and a row can never change a cell.
- `memberIds` are jurisdiction ids on `COMMANDERY` rows and spatial province ids on `JURISDICTION` rows, matching the grid the component was computed from.
- Gate proof (red probe on the real map, throwaway ledger): full coverage exits 0; dropping one row reports `UNADJUDICATED` and exits 1; a wrong `cellCount` reports `CELL_DRIFT` and exits 1; a `WATER_SEPARATED` verdict on a piece with land neighbours reports `NOT_WATER_SEPARATED` and exits 1.

### Task 5: Follow-ups this plan deliberately does not do

- `PARENT_MISASSIGNMENT` rows → separate small parent-ledger PRs (one commandery group each), each starting from a failing test on the target parent.
- `GEOMETRY_DEFECT` rows → the county-shape PR (protruding/elongated counties, strait and lake cuts) that reshapes cells with historical anchors, never by proximity fill.
- `UNKNOWN` rows stay preserved until a source is found; they are listed in the task report.
