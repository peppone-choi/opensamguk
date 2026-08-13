# OPENSAM-108 국내 삼모 계열 시스템 차분 조사 독립 리뷰

## Scope and fingerprint

- 리뷰 대상: `docs/superpowers/research/2026-08-13-opensam-108-domestic-sammo-differential.md` 한 파일과 그 파일이 인용한 저장소 코드·문서, 공개 GitHub issue, 로그인 없는 공개 URL.
- 리뷰 방식: 독립 read-only 적대 검토. 로그인, 계정 생성, 폼 제출, 대량 수집, 외부 서비스 변경은 하지 않았다.
- worktree: `/private/tmp/opensam-op108-sammo-research`
- `HEAD` = `origin/main` = `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`.
- 대상 파일은 리뷰 시점에 **untracked 신규 파일**이었다. 따라서 tracked predecessor와의 textual diff는 없고, exact-file review를 수행했다.
- 최초 전체 검토 대상: 201 lines, SHA-256 `6c1a63e7d23307e480ea0245da9a55a549245dbb41b50dee4539b5919c1458e8`.
- 리뷰 작성 중 다른 소유자가 대상 파일을 변경했다. 최종 재검토 대상은 201 lines, SHA-256 `82b9acf2cae084abc3b0476ce351efc746ecffe41a03bd223badbd060af427bb`이다. 관찰된 변경은 `:90`의 “지도 canvas”를 “지도 영역”으로 좁힌 문구이며 아래 finding을 해소하지 않는다. 연구 파일은 이 리뷰어가 편집하지 않았다.
- 이 리뷰의 판단 기준: GitHub #251 AC(각 서버 조사, devsam 대비 신규/변경/제거 + 근거 URL, 종합 후보), 상위 #250 격리 원칙, `os-review` runbook, 현재 저장소 코드.

## Findings

### MAJOR 1 — 공개 소스 원장과 UNKNOWN 경계가 동일 조사일의 실제 공개 상태와 어긋난다

- 위치: 연구 파일 `:28-31`, `:39-40`, `:95`, `:106`, `:109`, `:139`, `:201`.
- 문제: 문서는 samnet 전투 상세가 cache miss로 실패했고 묘삼 원문 세 URL을 재열람하지 못했다고 기록한다. 같은 2026-08-13 독립 재검증에서는 로그인 없이 네 URL 모두 HTTP 200이었다.
  - `https://www.samnet.kr/logs/battle/53`은 공개 HTML에 `BATTLE` payload를 싣고 있으며 `siege` 40턴, 매 턴 `shiro`, `atk_sol`, `siege_dmg`, `wall_loss`, 최종 결과를 제공한다. 따라서 “상세 replay 전체 UNKNOWN”과 `DOM-09`의 “상세 replay UNKNOWN”은 범위가 지나치게 넓다. **피해 공식·RNG·서버 판정은 여전히 UNKNOWN**이지만 공개 replay schema와 관측 값은 CURRENT-PUBLIC이다.
  - `http://www.myosam.com/dokuwiki/doku.php?id=help:start`, `...help:start:peq:peq`, `...help:start:basic:myostart`는 모두 공개 HTTP 200이었다. Q&A에는 City-oriented 자기규정, 도시병사 0/부족 시 공백지화, 국가 금·병량의 도시 이전이 보이며 페이지 표시는 마지막 수정 `2009-04-05`다. 시작·특징 페이지에는 도시 단위 금·병량이 보이며 마지막 수정 `2009-09-12`다.
- 실제 위험: 증거 등급이 “현재 접근 가능 여부”와 “콘텐츠가 오래됐는지”를 한 축으로 섞는다. 그 결과 접근 가능한 1차 자료가 2차 연구 요약으로 강등되고, 반대로 실제 공개 replay 후보는 누락된다. GitHub #251의 근거 URL·차분 AC를 닫았다는 주장도 부정확해진다.
- 권장 수정: URL별 `observed_at`, HTTP 상태, 페이지 자체의 last-modified/content vintage를 분리한다. 묘삼은 `CURRENTLY-ACCESSIBLE / HISTORICAL-CONTENT`처럼 두 축으로 표기하고 직접 인용 가능한 규칙에는 1차 URL을 붙인다. samnet replay는 공개 schema/관측 값을 기록하되 공식·RNG·authoritative mechanics는 UNKNOWN으로 유지한다. cache miss는 사용한 브라우저 도구의 실패로만 남기고 URL 부재로 일반화하지 않는다.
- 확신: 높음. 로그인 없는 `curl -L`과 공개 HTML 본문을 직접 확인했다.

### MAJOR 2 — samnet 공개 UI 관찰을 devsam 차분으로 분류했지만 devsam 오라클 대조가 없다

- 위치: 연구 파일 `:43-52`, `:87-95`, `:113-123`, `:127-139`, 결론 `:199`.
- 문제: GitHub #251은 devsam 대비 신규/변경/제거를 요구한다. 그러나 samnet의 2D/3D 전환, 공개 feed, 시작도시 picker, 황건적 진입점을 관찰한 뒤 `UI 신규/변경`, `온보딩 변경`, `이벤트 신규 후보`로 분류하면서 해당 기능이 PHP `legacy/devsam-core/hwe/ts` 또는 devsam 서버 표면에 없다는 path:line 근거를 제시하지 않는다. 이 worktree에는 `legacy/devsam-core` 자체도 없어 독립 PHP 대조가 불가능했다. 현재 OpenSamguk 코드와의 비교는 devsam 차분의 대체물이 아니다.
- 실제 위험: “다른 서버에서 보였다”는 제품 관찰이 “devsam보다 개선된 검증 차분”으로 승격된다. 이 오류가 `DOM-04`~`DOM-07` 우선순위와 후속 티켓 범위를 떠받친다. 제거된 devsam 명령·기능도 전혀 판정하지 못했지만, 조사 완료 상태가 이를 충분히 드러내지 않는다.
- 권장 수정: 각 samnet 행을 `(a) 공개 관찰`, `(b) devsam PHP/Vue 존재 여부`, `(c) 신규/변경/제거 판정`으로 분리한다. PHP 오라클이 없는 항목은 `DIFFERENTIAL-UNKNOWN / PRODUCT-CANDIDATE`로 낮춘다. 후속 PHP source path:line 대조를 수행하기 전에는 “신규/변경”을 UI 레이아웃 수준 이외의 시스템 차분으로 쓰지 않는다. 제거 판정은 별도 UNKNOWN 행으로 명시한다.
- 확신: 높음. 연구 파일에 devsam source path:line이 없고, worktree의 legacy oracle 부재를 확인했다.

### MAJOR 3 — 현재 OpenSamguk의 92/92 설명이 registry 사실과 감사 inventory를 혼동한다

- 위치: 연구 파일 `:47`, `:115`; 인용 문서 `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md:57,235,402,608`.
- 문제: 연구는 `CommandRegistry.resolve()`가 일반·국가·외교·전투 명령을 등록하고 “registry 92/92”라고 요약한다. 실제 코드는 `logic/.../actions/CommandRegistry.kt:132-235`의 reserved general action resolver이고, 즉시 명령은 별도 `CommandWireMapper.intakeCodes` 및 daemon dispatcher 경로다. 인용 감사도 `:402`에서 기존 92/92 수치를 **audit inventory**라고 한정하며, `:57`/`:608`의 92 고유 명령은 form→intake→daemon→flush→terminal 전체 matrix다.
- 실제 위험: 독자는 한 registry가 devsam 명령 전체를 열거한다고 오해하고, 새로운 후보의 admission/wire 충돌 및 기존 즉시 명령 중복을 잘못 판단할 수 있다.
- 권장 수정: “PHP 93파일/92 고유 명령의 end-to-end inventory가 비운영 PASS”로 고치고 reserved `CommandRegistry`, immediate `CommandWireMapper`, 별도 join/bulk 경로를 분리 기술한다. `DOM-02` 등 새 capability가 어느 경로를 소비하는지도 그 구분 위에서 정한다.
- 확신: 높음. 현재 `origin/main` 소스와 인용 감사 문구를 직접 대조했다.

### MINOR 1 — 후속 Draft A~C는 선택지와 선행 티켓이 모호해 바로 착수 가능한 계약이 아니다

- 위치: 연구 파일 `:141-177`.
- 문제: Draft A는 `OPENSAM-150/151/155`와의 파일·데이터 소유 경계를 정하지 않고, Draft B는 신규 draft인지 `OPENSAM-113` consumer인지 `or`로 남긴다. Draft C의 “v2 account/profile/possession 계약”은 티켓 ID와 완료 조건이 없다. 반면 실제 기존 티켓 `OPENSAM-150~155`(#325~#330)은 순차 의존, T2 편집 경계, flush/read 계약까지 구체적으로 소유한다.
- 실제 위험: draft를 tracker로 옮기면 기존 도시 원장 티켓과 중복되거나 shared-file single-writer 규율을 침범하고, Draft B는 공개 unauthenticated projection인데 인게임 UI 티켓인 OPENSAM-113에 잘못 귀속될 수 있다.
- 권장 수정: 각 draft에 `new ticket` 또는 `existing issue amendment` 하나만 선택하고, issue ID, owner surface, 선행 issue의 충족 조건, 비범위, 첫 검증 명령을 적는다. Draft A는 OPENSAM-151/155와의 생산자·projection 경계를, Draft B는 gateway/public API 소유 경계를 명시한다.
- 확신: 중간-높음. #325~#330 본문과 repo ticket ledger를 직접 확인했다.

## Confirmed strengths / no finding

- 인증 영역을 이번 current evidence에서 재사용하지 않고 UNKNOWN으로 둔 원칙은 적절하다.
- samnet 첫 화면과 가입 화면의 공개 관찰(2D 버튼, 황건적 진입점, 상·중·하순, 시작도시 목록, 세력 성·인원 수)은 로그인 없이 재현됐다.
- 현재 코드에서 `City`에 도시 금·병량·garrison이 없고 `Nation`/`General`에 금·병량이 있으며, `officerCntByCity`가 관직 2~4 담당도시 수입에 쓰인다는 설명은 확인됐다.
- `CONTROL_BUTTONS`는 실제 1..20이고 `MainControlBar`는 permission/officer/nation/secret gate를 적용한다.
- `OPENSAM-150~155`, `OPENSAM-41`, `OPENSAM-53`, `OPENSAM-113`, `OPENSAM-157`, `OPENSAM-171`, `OPENSAM-173`은 공개 GitHub issue 또는 tracked ticket ledger에서 존재를 확인했다. 도시 원장·3D·공성 관련 새 티켓 중복을 경계한 방향은 타당하다.
- 이 문서만 바뀌는 조사 작업이므로 RNG draw, 반올림, byte log, daemon write, 삽입 순서의 런타임 패러티 변경은 없다. 위험은 후속 티켓이 v1에 들어가지 않도록 v2 profile/DB/route/Flyway에 격리하는 데 있다.

## Executed checks

- `git rev-parse HEAD`, `git rev-parse origin/main`, `git status --short`, target SHA-256/line count.
- 대상 파일 전체 line-numbered read; `.ai/task.md`, `.ai/decisions.md`, project overview, review runbook 및 코드 리뷰 prompt 확인.
- 공개 URL 8개 unauthenticated GET/redirect 확인: samnet root/register/battle #53, Myosam help root/Q&A/start, GitHub #250/#251. 전부 HTTP 200이었다.
- samnet root/register/battle HTML과 Myosam Q&A/start 본문을 bounded pattern inspection했다.
- GitHub public API로 #250/#251 및 #325~#330 본문, OPENSAM-41/53/113/157/171/173 존재를 확인했다.
- 현재 코드 직접 대조: `LogicEntities.kt`, `CommandRegistry.kt`, `CommandWireMapper.kt`, `ProcessIncome.kt`, `GameChrome.tsx`, `MainControlBar.tsx`, `control-bar-config.ts`.
- 과거 묘삼/samnet 연구, v1 equivalence audit, ADR-LITE-021/022, v2 ticket ledger를 대조했다.

## Unexecuted / unavailable checks

- 로그인, 계정 생성, 폼 제출, 인증 후 samnet 기능 관찰: 범위 및 권한상 미실행.
- public URL 전체 crawl/37페이지 재수집: bulk scraping 금지에 따라 미실행. 이번 리뷰는 연구가 직접 인용한 3개 Myosam URL만 확인했다.
- PHP `legacy/devsam-core` path:line 대조와 golden capture: 이 worktree에 `legacy/devsam-core`가 없어 미실행. MAJOR 2의 핵심 미검증으로 남는다.
- 배포된 `sam.peppone.dev` 관찰: 연구가 repository evidence로 범위를 제한했고, 이 리뷰도 배포 상태를 주장하지 않는다.
- 테스트/빌드: 문서-only 리뷰이고 런타임 코드를 수정하지 않아 미실행.
- CodeGraph: 이 worktree에 usable `.codegraph` directory가 없어 선행 query가 실행되지 않았다. 반복 재시도하지 않고 bounded direct source read로 대체했다.
- 웹 도구의 samnet battle/Myosam/GitHub cache fetch는 일부 실패했지만, 동일 공개 URL을 unauthenticated direct GET으로 복구해 HTTP와 본문을 확인했다. 이는 사이트 실패가 아니라 도구/cache failure로 격리했다.

연구 파일의 소스 상태·samnet replay 범위·devsam 차분 분류·92/92 설명을 고치고 위 finding을 재검증하기 전에는 GitHub #251 AC를 닫거나 후속 티켓을 발행하면 안 된다.

Historical verdict: fix-required

---

## Re-review — corrected research artifact

### Exact basis

- Re-review date: 2026-08-13 (Asia/Seoul).
- `HEAD` = `origin/main` = `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`.
- Corrected research artifact: 218 lines, SHA-256 `002dc50cfc0c00cd079b207fc4a0f939a6051d15be0c78dbbd30f5aa27c5caa0`.
- Basis: exact-file reread of all 218 lines plus the repository sources and public evidence already inspected in the first review. No further login, form submission, bulk crawl, or external mutation was performed.

### Prior finding disposition

#### MAJOR 1 — current access, historical content, and public battle replay

**Resolved.**

- `:15-18` now separates current unauthenticated access from content age.
- `:29-31` records current HTTP 200 access separately from the Myosam pages' displayed 2009 modification dates; `:39` correctly keeps current runtime behavior `UNKNOWN`.
- `:28`, `:97`, `:108`, and `:218` now record battle #53's public 40-turn `siege` payload and observed fields while keeping damage formulas, RNG, authoritative server decisions, and unobserved battle types `UNKNOWN`.
- The cache failure is correctly isolated as a browser/cache-tool failure recovered by unauthenticated HTTP GET (`:39-40`), not generalized into source unavailability.

#### MAJOR 2 — samnet product observation versus devsam differential

**Resolved.**

- `:86` explicitly states that this worktree lacks the PHP `legacy/devsam-core` and `hwe/ts` oracle.
- Every samnet row at `:90-97` is classified `DIFFERENTIAL-UNKNOWN`; the product observation and the claims that remain unavailable are separate columns.
- `:125`, `:136`, and `:216` preserve these as product-pattern candidates rather than claiming devsam system novelty. No PHP source differential or removal claim is invented.

#### MAJOR 3 — 92-command inventory versus execution-path registries

**Partially resolved; one fix remains.**

- `:47` is now accurate: the 92 commands are an end-to-end form→intake→daemon→flush→terminal audit inventory, not the size of one registry. It also separates reserved `CommandRegistry`, immediate `CommandWireMapper`/dispatcher, and join/bulk paths.
- However, the synthesis table at `:117` still says **`v1 registry 92/92 + daemon immediate intake`**. That is the exact conflation the correction removes at `:47` and contradicts the repository evidence (`CommandRegistry.kt:132-235`, `CommandWireMapper.kt:43-109`, equivalence audit `:57,402,608`). A reader consuming only the summary table is still told that one registry owns 92/92.
- Required remediation: replace `:117` with wording such as `v1 end-to-end 92-command inventory; reserved registry + immediate daemon intake + separate join/bulk paths`.
- Severity: **MAJOR** because the incorrect summary controls duplicate/admission analysis for future capability tickets.
- Confidence: high.

#### MINOR 1 — Draft A–C disposition, ownership, start condition, and validation

**Resolved.**

- Draft A (`:151-162`) chooses a new ticket, assigns calculator/consumer ownership, excludes OPENSAM-150/151/155 schema/producer/read surfaces, requires those tickets merged, and names focused tests plus the backend gate.
- Draft B (`:168-178`) chooses a new unauthenticated public projection ticket, excludes OPENSAM-113 and producer/schema/authenticated UI ownership, and explicitly marks the producer/route issue ID `UNKNOWN`, hence not startable until identified and merged. Its unauthenticated/allowlist/empty-stale validation is concrete.
- Draft C (`:184-194`) is explicitly `HOLD`, forbids tracker issuance/start while the foundation issue is `UNKNOWN`, bounds ownership, and supplies candidate-ID/precheck contract validation.
- A research draft may truthfully be non-startable when the missing dependency is explicitly named and issuance is prohibited; that is preferable to inventing an issue identity.

### Re-review checks

Executed:

- Recomputed exact research SHA-256 and line count.
- Re-read the full corrected artifact with line numbers.
- Rechecked each former finding against its corrected locations and the already-inspected current repository paths.
- Confirmed the review modified only this assigned review artifact; the research artifact was not edited by this reviewer.

Unexecuted / unchanged limitations:

- PHP oracle path:line differential remains unexecuted because `legacy/devsam-core` is absent; the research now labels the resulting samnet differential `UNKNOWN`.
- Authentication, account creation, form submission, and bulk crawling remain unexecuted by contract.
- Runtime build/test remains inapplicable to this documentation-only correction; no runtime source changed.
- Previously documented browser/cache and CodeGraph availability failures remain isolated baselines and do not support completion claims.

The corrected artifact resolves three prior findings completely and most of the 92-inventory finding, but the stale `registry 92/92` synthesis statement at `:117` must be corrected before clearance.

Historical re-review verdict: fix-required

---

## Final bounded re-review

### Exact basis

- `HEAD` = `origin/main` = `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`.
- Final research artifact: 218 lines, SHA-256 `aba787d39329fc632ab3c6cef8e141bab25a328d64bcfbdbcb559e1b3a94811d`.
- Bounded scope: the remediated synthesis row plus every previously reviewed evidence boundary and Draft A–C contract. No new browsing, authentication, form submission, bulk collection, runtime mutation, or research-file edit was performed.

### Final disposition

- Current access versus historical content remains correctly separated at `:15-16`, `:29-31`, `:39`, and `:57`.
- Battle #53 remains bounded to the public 40-turn `siege` payload and observed fields at `:28` and `:97`; formulas, RNG, authoritative decisions, and unobserved battle types remain `UNKNOWN` at `:97`, `:108`, and `:218`.
- Samnet observations remain `DIFFERENTIAL-UNKNOWN` without the PHP/Vue oracle at `:86-97`, `:125`, and `:216`; no system novelty or removal is invented.
- The end-to-end 92-command audit is accurately split at `:47`. The remediated synthesis at `:117` now says `v1 E2E audit 92/92; reserved/immediate/join·bulk 경로 분리`, removing the last false single-registry implication.
- Draft A–C retain explicit disposition, non-overlapping ownership, start/HOLD conditions, and first validation at `:151-162`, `:168-178`, and `:184-194`. Unknown dependency issue identities are not fabricated.
- Previously confirmed parity/product safeguards remain: v1 is not modified, candidates are v2-only sanctioned divergence, existing ticket duplication is checked, and public UI evidence is not promoted to mechanics evidence (`:207-212`).

### Checks and limitations

Executed: exact SHA/line count, focused line-numbered reread, stale-phrase search, and consistency check across the evidence ledger, differential table, synthesis, candidate list, drafts, risks, and conclusion.

Unexecuted limitations are unchanged and correctly disclosed by the research: PHP oracle differential (legacy absent), authenticated surfaces, bulk crawl, and deployed OpenSamguk behavior. The documentation is internally consistent within its observed evidence, but the missing PHP/Vue differential still blocks OPENSAM-108 acceptance and any release decision that depends on that differential.

No BLOCKER, MAJOR, MINOR, or QUESTION finding remains in the assigned scope.

Evidence-scope verdict: cleared

Ticket acceptance/release verdict: `INCOMPLETE_BLOCKED` until the PHP/Vue source path:line differential is executed and reviewed.
