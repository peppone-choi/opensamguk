# check.py 단일 스캔 전환(#513) 독립 적대적 비평

Scope: work/opensamguk/check-py-single-walk @ 835ad413 (daf60a10..835ad413) 대 origin/main — check.py 의 ROOT.glob() 24회 순회를 git ls-files 1회 열거로 바꾼 변경의 커버리지 동일성·뮤테이션 민감도·비-git 계약·경로 파싱·gitignore 부작용·성능 주장 재검증 (초심 60836eaf, 재심 1차 d8e098cc, 재심 2차 835ad413)

Verdict: cleared

**판정 이력**: 초심(`60836eaf`) = cleared → 재심 1차(`d8e098cc`) = fix-required → 재심 2차(`835ad413`) = cleared. `835ad413` 이 MED-1 을 닫았다(§8). 남은 지적은 LOW 2건뿐이고 수정 요구 없음.

재심 1차에서 fix-required 였던 이유(기록 보존): **`2d099e91` 이 `.gitignore` 를 `.env*` 와일드카드로 넓히면서 이 저장소가 템플릿으로 추적 중인 `.env.example` 4개를 ignore 대상으로 만들었다**(MED-1). #513 스캔에는 영향이 없었으나 `git add -A` 가 새 `.env.example` 을 조용히 건너뛰었다.

**#513 본론 판정 근거(변동 없음)**: 커버리지 차집합을 실제로 뽑아 전수 판정했고(빠진 607개 전부 gitignore 된 빌드/스크래치 산출물), 이슈가 기각한 "디렉터리 이름 기반 pruning" 으로 회귀하지 않았음을 뮤테이션으로 확인했으며, 비-git 계약 변경은 CLI 관점에서 무변화임을 before/after 동일 트레이스백으로 입증했다.

---

## 0. 검토 환경 (측정 신뢰도에 영향)

- 검토 중 **다른 에이전트가 워크트리의 `tools/agent-system/check.py` 를 origin/main 판으로 되돌린 상태**가 관측되었다(`git -C <worktree> status --short` → ` M tools/agent-system/check.py`, diff 는 정확히 이번 fix 의 역방향). 자기 before 측정을 돌리는 중으로 보인다.
- 따라서 이후 모든 측정은 워크트리 파일이 아니라 **`git archive 60836eaf` 로 뽑은 정본 사본**(after)과 **`git show origin/main:tools/agent-system/check.py`** (before)로 수행했다. 두 사본은 커밋과 바이트 동일함을 `diff -q` 로 확인했다.
- 검토 시점 머신 부하: `uptime` → `load averages: 155.49 136.41 75.99`. 시간 측정 항목(§6)은 이 조건에서 읽어야 한다.

---

## 1. 커버리지 축소 여부 — 차집합 전수 판정 (핵심 공격 지점)

"findings 가 같다"는 커버리지 동일성의 증거가 아니라는 지적에 동의한다. 그래서 **파일 집합 자체의 대칭차집합**을 뽑았다.

### 1-1. 깨끗한 체크아웃: 차집합 0

`ROOT.glob()` 경로와 `scannable_repo_files()+regex` 경로가 만드는 `source_surfaces` 집합을 같은 프로세스에서 동시에 계산해 비교:

```
$ python3 covdiff.py <worktree>
old glob: 1069 files in 2.84s
new scan: 1069 files in 0.73s (ls-files total 3588)
ONLY-OLD (lost coverage): 0
ONLY-NEW (gained): 0
```

### 1-2. 오염 체크아웃: 빠지는 607개를 전수 분류

차집합은 이론적으로 정확히 "디스크에 있으면서 untracked AND ignored" 집합이다(`--cached` 가 tracked 전부를, `--others` 가 untracked-unignored 전부를 덮으므로). 그 집합을 실제로 열거해 glob·`is_active_product_authority_source()` 를 통과하는 것만 남기면:

```
$ python3 lost3.py <fix-copy> /Users/apple/.../projects/opensamguk
ignored+untracked on disk: 145501
  matching a glob: 80261   surviving is_active: 607
   237  web/gateway/.next
   122  web/game/.next
     4  .agents/skills/*/…(higgsfield-brandkit, higgsfield-youtube-thumbnail, java-spring-boot, java-testing)
     1  .omo/teams/team-67fb6e8b/artifacts/F-runtime-audit-compose.override.yml
   243  .playwright-mcp/page-*.yml, docker-compose.headroom.yml
```

**판정: 607건 전부 검사 대상이 아닌 게 맞다.** Next.js 빌드 산출물(`.next/`), playwright 덤프, 내려받은 외부 스킬 자산, OMC 팀 스크래치, 로컬 전용 compose 파일. 저장소 제품 소스는 0건.

그리고 그중 4건은 **before 에서 실제로 거짓 ERROR 를 내던 파일**이다 — 이슈 본문의 "거짓 ERROR" 주장이 실측으로 확인된다:

```
ignored-but-would-have-been-scanned files: 607
of those, would raise an obsolete-authority ERROR: 4
    ('web/game/.next/server/app/game/map/page.js', 'php 정본')
    ('web/game/.next/server/app/page.js', 'byte-parity')
    ('web/game/.next/static/chunks/app/game/map/page.js', 'php 정본')
    ('web/game/.next/static/chunks/app/page.js', 'byte-parity')
```

(매칭 글롭은 `web/**/*.js` 로 확인.)

### 1-3. 지적된 개별 누락 시나리오 개별 판정

| 시나리오 | 판정 | 근거 |
|---|---|---|
| **추적되지만 무시 패턴에 걸리는 파일** | 안 빠진다 | `--cached` 는 ignore 여부와 무관하게 tracked 전부를 낸다. 추가로 `git ls-files -z \| xargs -0 git check-ignore` → 출력 0줄(현재 tracked 중 ignore 되는 파일 자체가 없음) |
| **서브모듈** | 해당 없음 | `.gitmodules` 없음, `git submodule status` 출력 없음 |
| **심링크** | 동등 | pathlib `**` 는 심링크 디렉터리를 따라가지 않고 git 도 마찬가지. tracked 심링크 파일은 `--cached` 에 나오고 양쪽 다 `is_active_...` 의 `path.is_file()` 을 통과 |
| **tracked 인데 디스크에서 지워진 파일** | 동등 | `--cached` 는 내지만 `is_active_...` 의 `path.is_file()` 이 False → 걸러짐. before 의 glob 도 못 찾음 |
| **`.gitignore` 로 무시되지만 검사돼야 마땅한 파일** | 없음 | 1-2 의 607건 전수 분류 결과 |
| **`.git/` 내부** | 개선 | before 의 pathlib glob 은 dotfile 을 거르지 않아 `.git/**/*.yml` 등을 훑었다. after 는 열거 자체에서 제외 |

### 1-4. 기각된 "이름 기반 pruning" 회귀 여부

회귀하지 않았다. 구현은 `os.walk` 를 쓰지 않고 디렉터리 이름 리터럴을 어디에도 넣지 않는다. 테스트도 `.omo` 가 아니라 `totally-unrelated-scratch-dir/`·`scratch-tree/` 라는 무관한 이름으로 "untracked+ignored" 라는 *성질* 을 검증한다. 아래 M1/M3 뮤테이션이 그 성질을 실제로 붙잡는 것도 확인했다.

---

## 2. 뮤테이션 — 핀포인트인가, 우회 경로는 없는가

샌드박스 사본(`git archive` 정본)에 뮤테이션을 넣고 `test_check_glob_pattern_equivalence.py` + `test_check_product_authority_scan.py` (7 tests) 를 돌렸다. 기준선은 저장소 전체 16/16 green:

```
$ python3 -m unittest discover -s tools/agent-system/tests -p 'test_*.py'
Ran 16 tests in 3.149s
OK
```

| # | 뮤테이션 | FAIL 수 | 어떤 테스트가 잡았나 |
|---|---|---|---|
| M1 | `--exclude-standard` 제거 | 2 | `test_violation_in_ignored_untracked_source_is_not_flagged`, `test_ignored_untracked_directory_is_excluded_by_property_not_name` |
| M2 | `--others` 제거 | 2 | `test_violation_in_untracked_but_not_git_add_ed_source_is_still_flagged`, `test_untracked_but_not_ignored_file_is_still_included` |
| M3 | `--cached` 제거 | 2 | `test_violation_in_tracked_source_is_still_flagged`, `test_ignored_untracked_directory_is_excluded_by_property_not_name` |
| M4 | `*` 가 `/` 를 넘게(`[^/]*`→`.*`) | 1 | 등가성 테스트 |
| M7 | 중간 `**` 가 0개 디렉터리를 못 맞게 | 1 | 등가성 테스트 |
| M8 | `$` 앵커 제거 | 1 | 등가성 테스트 |
| M10 | `-z` 제거 | 4 | 스캔 테스트 4개 전부 |
| M5 | `?` 가 `/` 를 넘게 | 0 (SURVIVED) | **등가 뮤턴트** — 현재 23개 글롭 중 `?` 를 쓰는 패턴이 0개 |
| M6 | 선두 `**` → `.*` | 0 (SURVIVED) | **등가 뮤턴트** — `^(?:.*/)?[^/]*\.yaml$` 와 `^.*[^/]*\.yaml$` 는 같은 언어("`.yaml` 로 끝남")를 인식 |
| M9 | `^` 앵커 제거 | 0 (SURVIVED) | **등가 뮤턴트** — 호출부가 `regex.match()` 라 이미 선두 고정 |

**판정: M1·M2·M3 이 서로 다른 테스트 쌍에 걸린다.** 세 플래그가 각각 독립적으로 감시된다는 뜻이고, "2 FAIL 이 사실 같은 테스트라 한 덩어리로 움직이는 것" 이라는 의심은 성립하지 않는다. 생존한 M5/M6/M9 는 전부 등가 뮤턴트로, 테스트 공백이 아니다(위에 각각 증명).

정렬 순서/중복 제거는 애초에 결과가 `set` 이고 소비부가 `sorted(...)` 라 우회 경로가 될 수 없다.

---

## 3. 비-git 계약 — 회귀 없음

`scannable_repo_files()` 가 `RuntimeError` 를 던지는 게 새 계약처럼 보이지만, **CLI 관점에서는 변화가 없다.** `main()` 이 `check_product_authority_policy()` 보다 먼저 `changed_files(args.base)` 를 부르고, 그게 `run_git(["ls-files", "--others", "--exclude-standard"])` 로 이미 같은 예외를 던진다. 비-git 디렉터리에서 before/after 를 각각 실행한 결과가 **동일한 트레이스백·동일한 지점**이다:

```
=== BEFORE (origin/main check.py) in non-git dir ===
    untracked = run_git(["ls-files", "--others", "--exclude-standard"]).splitlines()
  File ".../nongit/tools/agent-system/check.py", line 506, in run_git
    raise RuntimeError(...)
RuntimeError: fatal: not a git repository (or any of the parent directories): .git

=== AFTER (fix) in non-git dir ===
    untracked = run_git(["ls-files", "--others", "--exclude-standard"]).splitlines()
  File ".../pristine/tools/agent-system/check.py", line 544, in run_git
    raise RuntimeError(...)
RuntimeError: fatal: not a git repository (or any of the parent directories): .git
```

호출부 전수 확인: `grep -rn "agent-system/check"` 결과 실제 실행 지점은 `.github/workflows/ci.yml:19` (`python3 tools/agent-system/check.py --strict --base origin/main`) 와 `ci.yml:33` (unittest discover) 뿐이고 나머지는 전부 문서 언급이다. 비-git 경로에서 부르는 곳은 없다. `scannable_repo_files()` 를 import 하는 외부 소비자도 신설 테스트 외에 없다.

---

## 4. `-z` / `surrogateescape` — 실파일 검증

일회용 git 저장소에 특수 문자 파일명을 실제로 만들고 위반 문자열을 넣어 end-to-end 로 돌렸다:

```
scannable: 6 expected 6
    'logic/src/main/kotlin/plain.kt' is_file= True
    'logic/src/main/kotlin/quote"and\\back.kt' is_file= True
    'logic/src/main/kotlin/tab\there.kt' is_file= True
    'logic/src/main/kotlin/with\nnewline.kt' is_file= True
    'logic/src/main/kotlin/with space.kt' is_file= True
    'logic/src/main/kotlin/한글.kt' is_file= True

violations flagged for weird-named files: 6/6
    'logic/src/main/kotlin/with\nnewline.kt:1 restores obsolete mandatory legacy authority: PHP is the grand truth'
    ... (space / tab / quote+backslash / 한글 / plain 모두 동일하게 검출)
```

개행·공백·탭·따옴표·역슬래시·비ASCII 전부 정상. 비-UTF8 파일명은 **이 머신에서 재현 불가**: APFS 가 거부한다 (`OSError: [Errno 92] Illegal byte sequence: .../non\xffutf8.kt`). → LOW-2 참고.

---

## 5. `.gitignore` 변경 부작용

- **이미 추적 중인 파일을 무시하게 만드는가 → 아니다.**
  ```
  $ git ls-files | grep -E '(^|/)\.omo/|(^|/)\.omx/|(^|/)\.codegraph|^common/src/main/kotlin/graphify-out/|(^|/)\.env\.bak\.'
  (exit 1 — 매칭 0건)
  $ git ls-files -z | xargs -0 git check-ignore
  (출력 없음)
  ```
  **재심 방법론 정정**: 위 두 번째 명령의 "출력 없음" 은 근거로 쓸 수 없다. `git check-ignore` 는 `--no-index` 없이는 **index 에 있는 경로를 아예 건너뛰므로** tracked 파일에 대해서는 항상 빈 출력이 나온다 — 공허한 확인이었다. 유효한 근거는 첫 번째 명령(신규 규칙 문자열을 `git ls-files` 에 직접 대조, 매칭 0건)이고 그 결론은 `60836eaf` 기준으로 유효하다. 올바른 확인법은 `git check-ignore --no-index -v` 이며, 그걸로 재심한 결과가 MED-1 이다.
- **커밋 메시지의 "local-only-exclude 였다" 주장 → 전부 사실.** 오염 체크아웃에서:
  ```
  .omo/                 .git/info/exclude:11:.omo/
  .omx/                 .git/info/exclude:7:.omx/
  .codegraph            .git/info/exclude:13:.codegraph
  .env.bak.1787326727   NOT IGNORED
  common/src/main/kotlin/graphify-out/   NOT IGNORED
  ```
  즉 세 개는 `.gitignore` 가 아닌 `.git/info/exclude` 에만 있었고(다른 머신에서는 안 먹음), 뒤 두 개는 아예 무시되지 않아 `--others` 로 새어 들어오는 게 맞았다.
- `.git/info/exclude` 에 남은 나머지(`.context/`, `.conductor/*`, `/legacy`, `/.claude/RESUME.md`)는 갭이 아니다: `.context` 는 tracked(6파일), `legacy/` 는 `.gitignore:26` 에 이미 있다.

---

## 6. 시간 측정의 공정성 — 주장 일부 미지지

같은 프로세스 구조·같은 warm 조건에서 before/after 를 **교대로** 돌렸다(측정 대상은 `check_product_authority_policy()` 한 함수).

깨끗한 워크트리, load average 155 환경:

```
BEFORE 81.03s  AFTER 26.19s  BEFORE 23.20s  AFTER 16.90s  BEFORE 39.43s
```

**판정: 깨끗한 체크아웃에서의 개선 주장(29.79/22.72 → 22.71/20.06)은 이 환경에서 지지되지 않는다.** 편차가 개선 폭보다 크다. 다만 이건 노이즈 문제이지 결함이 아니다 — 그리고 §1-1 에서 `ROOT.glob()` 부분만 떼어 측정한 2.84s vs 0.73s 는 방향이 일관된다.

**이슈의 본론인 오염 체크아웃에서는 주장이 확실히 성립한다:**

```
AFTER(polluted)  36.65s findings=0
BEFORE(polluted) DID-NOT-COMPLETE exit=124   (timeout 420s)
```

after 는 완주하고 findings 0, before 는 420초 예산에서 미완주. `scannable_repo_files()` 실측도 구현자 주장과 일치한다:

```
scannable_repo_files: 3675
  from .omo/: 0
  from node_modules: 0
```

---

## 지적 사항

### MED-1. ~~`2d099e91` 의 `.env*` 와일드카드가 추적 중인 `.env.example` 템플릿을 가린다~~ → 해소됨 — `835ad413` (검증: §8)

`2d099e91` 은 `.env` / `.env.local` / `.env.headroom.example` / `.env.bak.*` 네 줄을 `.env*` 한 줄로 대체했다. 그런데 이 저장소는 **`.env.example` 을 커밋되는 템플릿으로 추적하고 있고, 그게 문서화된 규약이다** — `AGENTS.md:185` "`.env*`는 git-ignore, `.env.example`을 템플릿으로 사용". 옛 4줄 목록은 `.env.headroom.example` 만 콕 집어 무시하고 `.env.example` 은 **의도적으로 남겨둔** 형태였는데, 와일드카드가 그 구분을 지웠다.

현재 추적 중이면서 새 규칙에 걸리는 파일 4개:
```
$ git ls-files | grep -E '(^|/)\.env'
.env.example
web/game/.env.example
web/gateway/.env.example
(+ v2-sandbox.env.example — 이건 `.env*` 에 안 걸림)

$ git check-ignore --no-index -v .env.example web/game/.env.example web/gateway/.env.example
.gitignore:19:.env*	.env.example
.gitignore:19:.env*	web/game/.env.example
.gitignore:19:.env*	web/gateway/.env.example
```

`.gitignore` 에 `!` negation 은 24개나 있지만 `.env` 계열에는 하나도 없다(`grep -n '^!' .gitignore` 로 확인 — `gradle-wrapper.jar`, `.agents/skills/**`, `.codex/**`, `data/map/han-tiles.json` 뿐).

**실제 사고 재현** — 이 브랜치의 `.gitignore` 를 그대로 넣은 일회용 저장소에서 `.env.example` 두 개를 만들고 `git add -A`:
```
--- staged after 'git add -A' ---
.gitignore
--- git status --short ---
A  .gitignore
```
`.env.example` 도 `web/admin/.env.example` 도 **스테이징되지 않고, `git status` 에 흔적도 남지 않는다.** 즉 누구든 새 웹앱을 붙이면서 `.env.example` 템플릿을 추가하면 조용히 누락된다. 웹앱 두 개가 이미 각자 `.env.example` 을 갖고 있으므로 세 번째가 생기는 건 가정이 아니다.

**#513 스캔 영향은 없다** — 이미 추적된 파일은 ignore 규칙과 무관하게 `--cached` 에 나온다:
```
scannable_repo_files: 3590
tracked .env.example still enumerated:
  ['.env.example', 'v2-sandbox.env.example', 'web/game/.env.example', 'web/gateway/.env.example']
```

**그래서 이건 #513 결함이 아니라 #513 브랜치에 얹힌 드라이브바이 변경의 결함이다.** 고치는 방법은 `.env*` 뒤에 negation 한 줄(`!.env*.example` 계열) 을 붙이거나, 애초에 `2d099e91` 을 이 브랜치에서 빼는 것 — 어느 쪽이든 구현자 판단이다. 다만 **이대로 머지하면 안 된다.**

**해소 — `835ad413`.** `2d099e91` 을 고쳐 쓰지 않고 새 커밋으로 얹었다. 검증은 §8. 다만 위에서 내가 제안한 "negation 한 줄" 은 **불완전한 처방이었다** — 한 줄만 넣으면 `.env.headroom.example` 이 되살아난다. 실제 수정은 3행이고 그게 맞다(§8-1).

---

## 지적 사항 (LOW — 수정 요구 아님)

### LOW-1. ~~등가성 테스트의 "새 패턴 자동 커버" 는 부분적으로만 참~~ → `d8e098cc` 에서 절반 해소, 나머지는 문서화됨
테스트 독스트링은 "PRODUCT_AUTHORITY_SOURCE_GLOBS 에서 읽으므로 새로 추가된 패턴이 자동으로 커버된다"고 쓰지만, `_glob_pattern_to_regex()` 는 `*`·`?`·`**` 만 번역하고 pathlib 이 지원하는 `[seq]` 문자 클래스는 `re.escape` 로 리터럴화한다. 게다가 fixture 파일 목록이 고정이라 새 패턴이 그런 문법을 쓰면 **테스트가 아무것도 못 잡는다**(양쪽 다 0개 매칭이 되어 통과할 수도, 조용히 발산할 수도 있다).

재현:
```
$ python3 -c "... check._glob_pattern_to_regex('[ab].kt') ..."
pathlib: ['a.kt', 'b.kt']
regex  : []
regex src: ^\[ab\]\.kt$
```
현재 24개 패턴 중 문자 클래스를 쓰는 건 0개이므로 **오늘의 결함은 아니다.** 잠재적. (참고로 dotfile 매칭은 양쪽 동등함을 별도로 확인했다: `**/*.yml` 에 대해 pathlib·regex 모두 `.hidden.yml`, `sub/.h.yml` 을 매칭.)

**재심 갱신 — 그리고 내 초심의 과소평가 정정.** 나는 "fixture 목록이 고정이라 새 패턴이 아무것도 못 잡을 수 있다"는 메커니즘까지는 짚었지만 **그게 오늘 이미 발생 중인지를 측정하지 않고 "잠재적" 으로 분류했다. 실제로는 24개 중 9개가 이미 공허 통과 중이었다.** `d8e098cc` 가 이걸 닫았다 — 검증은 §7-2. 문자 클래스 발산 자체는 여전히 남아 있으나 `d8e098cc` 가 `_glob_pattern_to_regex()` 독스트링에 "bracket classes / `a**b` 는 지원하지 않으니 쓰려면 함수를 먼저 확장하라"고 명시해, 잠재 위험이 코드에 문서화된 상태다. 이 이상은 요구하지 않는다.

### LOW-2. `surrogateescape` 는 이 저장소의 테스트로 증명되지 않는다
뮤테이션 M11(`surrogateescape` → `replace`)이 **생존**했다. 비-UTF8 파일명 테스트가 없기 때문이고, macOS(APFS)에서는 그런 파일을 만들 수 없어 이 머신에서는 테스트를 쓸 수도 없다(§4의 `Errno 92`). Linux CI 에서는 재현 가능하지만 실효 위험은 낮다: 잘못 디코드돼도 `ROOT / rel` 이 존재하지 않는 경로가 되어 `is_file()` 에서 걸러질 뿐 오검출을 만들지 않는다. 기존 `parse_name_status_z()` 와 같은 관례를 따른 방어 코드로 보면 되고, 굳이 테스트를 강요할 근거는 약하다.

### LOW-3. ~~"CLAUDE.md 의 `.env*` 문장을 수정했다" 는 주장은 사실이 아니다~~ → `2d099e91` 에서 해소됨 (단, MED-1 을 새로 만들었다)
브랜치에 **CLAUDE.md 변경이 없다** (`git diff origin/main...HEAD --stat -- CLAUDE.md` → 출력 없음; 전체 diffstat 도 `.gitignore` / `check.py` / 신규 테스트 2개, 4 files only). 그리고 `CLAUDE.md:79` 는 여전히 ``Git-ignored: … `.env*` …`` 라고 적고 있는데, `.gitignore` 의 실제 리터럴은 `.env`, `.env.local`, `.env.headroom.example`, (이번에 추가된) `.env.bak.*` 뿐이고 `.env.example` 은 tracked 다. 즉 문서↔설정 불일치는 **남아 있다** — 애초에 `.env.bak.1787326727` 이 unignored 로 떠다닌 게 그 증거다.

영향은 이번 스캔 경로에는 없다(가상의 `.env.staging` 은 `--others` 에 뜨더라도 24개 글롭 어디에도 안 걸린다). 이슈 #513 범위 밖이므로 이 브랜치에서 고치라고 요구하지 않는다. 다만 **구현자 보고에서 이 항목은 삭제하거나 "미수행" 으로 정정해야 한다.**

**재심 갱신 — 이 지적은 `2d099e91` 로 해소되었다.** 다만 해소 방향이 내가 예상한 쪽(문서 수정)이 아니라 **설정이 문서를 따라간 쪽**이다: CLAUDE.md 는 그대로 두고 `.gitignore` 를 `.env*` 로 넓혔다. 문서↔설정 불일치라는 지적 자체는 정당하게 닫혔다. 다만 그 방식이 **MED-1 을 새로 만들었다** — `.env*` 는 CLAUDE.md 의 문장과는 일치하지만 `AGENTS.md:185` 의 "`.env.example` 을 템플릿으로 사용" 규약과 충돌한다. 초심에서 이 항목을 "사실이 아니다" 로 적은 것은 `60836eaf` 스냅샷 기준으로는 맞았다(그 커밋에 CLAUDE.md 변경은 실제로 없었다).

---

## 7. 재심: `b95c61a5` / `2d099e91` / `d8e098cc` 델타 (`60836eaf` → `d8e098cc`)

델타 diffstat: `.gitignore` (+1/-4), `check.py` (+8/-1, 독스트링만), 등가성 테스트 (+27/-6), 스캔 테스트 (+2/-2, 주석의 23→24), 그리고 다른 리뷰어의 검토 문서 1건. **실행 코드 변경은 0줄이다** — `_glob_pattern_to_regex()` 본문도 `scannable_repo_files()` 본문도 손대지 않았다.

### 7-1. `2d099e91` (`.env*`) → MED-1 참조

문서↔설정 불일치는 닫혔으나 추적 중인 템플릿 4개를 가린다. 근거·재현은 MED-1 에 있다. #513 스캔 자체에는 영향 없음(`scannable_repo_files: 3590`, `.env.example` 4개 모두 여전히 열거됨).

### 7-2. `d8e098cc` 의 공허 통과 주장 — 9개 전부 사실

구현자 주장("현재 24개 패턴 중 9개가 이미 공허 통과 중이었다")을 옛 fixture 목록으로 직접 재계산했다:

```
old fixtures: 23   new fixtures: 32   added: 9

VACUOUS under OLD fixtures: 9
   - **/src/main/**/*.java
   - **/src/baseline/**/*.kts
   - web/**/*.js
   - web/**/*.mjs
   - web/**/*.cjs
   - tools/**/*.ts
   - tools/**/*.js
   - tools/**/*.mjs
   - tools/**/*.cjs
VACUOUS under NEW fixtures: 0
```

**개수·목록 모두 주장과 일치한다.** 그리고 추가된 fixture 가 "가드만 통과시키려고 억지로 맞춘 파일" 인지 확인하려고 각 신규 파일을 24개 패턴 전체에 매칭시켜 봤다 — **9개 파일이 9개 결손 패턴에 정확히 1:1 로, 각각 딱 하나씩만 걸린다.** 곁다리로 다른 패턴을 건드려 기존 검증의 의미를 흐리는 파일은 없다:

```
logic/src/baseline/kotlin/sub/Bar.kts  -> ['**/src/baseline/**/*.kts']
logic/src/main/java/Foo.java           -> ['**/src/main/**/*.java']
tools/a/b/c/x.ts                       -> ['tools/**/*.ts']
tools/a/b/c/x.js                       -> ['tools/**/*.js']
tools/a/b/c/x.mjs                      -> ['tools/**/*.mjs']
tools/a/b/c/x.cjs                      -> ['tools/**/*.cjs']
web/game/src/x.js                      -> ['web/**/*.js']
web/game/src/x.mjs                     -> ['web/**/*.mjs']
web/game/src/x.cjs                     -> ['web/**/*.cjs']
```

특히 `web/game/src/x.js` 가 `web/**/*.ts` 에 안 걸리고 `tools/a/b/c/x.ts` 가 `web/**` 에 안 걸리는 것 — 즉 확장자·프리픽스 구분이 살아 있는 것 — 을 같은 표로 확인했다.

### 7-3. 가드 자체가 뮤테이션에 걸리는가 — 걸린다

fixture 를 한 줄씩 지워봤다:

| 뮤테이션 | FAIL | 메시지 |
|---|---|---|
| `"web/game/src/x.mjs"` 삭제 | 1 | ``pattern 'web/**/*.mjs' matched zero files in FIXTURE_FILES -- this test can't prove regex/glob equivalence for it.`` |
| `"logic/src/main/java/Foo.java"` 삭제 | 1 | ``pattern '**/src/main/**/*.java' matched zero files in FIXTURE_FILES ...`` |

가드가 **어느 패턴이 비었는지 이름까지 찍고** 죽는다. (가드 자체를 `assertTrue(True, ...)` 로 무력화하고 fixture 는 그대로 두는 뮤테이션은 당연히 통과한다 — 메타 어서션이라 잡을 대상이 없다. 공백 아님.)

### 7-4. 가드가 다른 것을 조용히 약화시켰나 — 아니다

fixture 가 23→32 로 늘면서 기존 등가성 검증이 헐거워졌을 가능성을 확인하려고, 초심에서 잡혔던 뮤테이션을 `d8e098cc` 에서 전부 재실행했다. 민감도 동일:

```
[M1 drop --exclude-standard] failing=2
[M2 drop --others]           failing=2
[M3 drop --cached]           failing=2
[M4 star crosses slash]      failing=1
[M7 mid ** no zero-dir]      failing=1
[M8 drop $ anchor]           failing=1
```

저장소 전체 스위트도 그대로 green: `Ran 16 tests in 2.642s / OK`.

### 7-5. 23→24 정정이 전부 반영됐나 — 됐다

```
patterns: 24
regexes : 24
root-recursive (** prefix): 9
```

실제 튜플 길이 24, 정규식 24개로 일치. "9 of them full-tree recursive" 라는 독스트링 문구도 실측 9와 일치한다. 브랜치 내 `tools/agent-system/**` 에 패턴 개수를 가리키는 stale `23` 은 남아 있지 않다(grep 결과 0건).

---

## 8. 재심 2차: `835ad413` (MED-1 수정본)

델타는 `.gitignore` 2줄 추가뿐(`git diff d8e098cc..835ad413 --stat` → `1 file changed, 2 insertions(+)`). 최종 규칙:

```
19: .env*
20: !.env*.example
21: .env.headroom.example
```

### 8-1. 3행이 필요했나 — 필요했다. 내 초심 처방이 틀렸고 팀리드 판단이 맞다

동일한 파일 8개를 심은 일회용 저장소를, **커밋된 3행판**과 **3행을 뺀 2행판**으로 각각 돌려 `git add -A` 결과를 비교했다:

| | as-committed (3행) | 3행 제거 (2행) |
|---|---|---|
| `.env` | 무시 | 무시 |
| `.env.local` | 무시 | 무시 |
| `.env.bak.1234` | 무시 | 무시 |
| `.env.example` | **스테이징** | **스테이징** |
| `web/game/.env.example` | **스테이징** | **스테이징** |
| `web/admin/.env.example` (신규 3번째) | **스테이징** | **스테이징** |
| `v2-sandbox.env.example` | **스테이징** | **스테이징** |
| `.env.headroom.example` | 무시 ✅ | **스테이징 ❌** |

2행판에서 `.env.headroom.example` 이 `!.env*.example` 에 걸려 되살아나 추적 대상이 된다. **3행이 그걸 되돌리는 유일한 줄이다.** 초심 MED-1 에서 내가 적은 "negation 한 줄(`!.env*.example` 계열)" 은 이 부작용을 짚지 못한 불완전한 처방이었다 — 팀리드의 3행 지시가 옳았다.

### 8-2. `check-ignore` 와 `git add` 가 갈리는가 — 갈리지 않는다

구현자 재현에서 빠졌던 `.env.headroom.example` 을 `add` 실경로로 직접 확인했다. 위 표의 as-committed 열이 그 결과이고, `check-ignore --no-index -v` 결과와 완전히 일치한다:

```
.gitignore:19:.env*                  .env
.gitignore:19:.env*                  .env.local
.gitignore:19:.env*                  .env.bak.1234
.gitignore:20:!.env*.example         .env.example
.gitignore:20:!.env*.example         web/game/.env.example
.gitignore:20:!.env*.example         web/admin/.env.example
.gitignore:21:.env.headroom.example  .env.headroom.example
```

`.env.headroom.example` 만 21행(비-negation)에서 끝나고 나머지 템플릿은 20행(negation, `!` 접두)에서 끝난다 — `add` 결과와 1:1 대응.

### 8-3. negation 이 다른 것을 조용히 되살렸나 — 아니다

`!.env*.example` 이 되살리는 건 `.env` 로 시작하고 `.example` 로 끝나는 경로뿐이다. `2d099e91` 이전 목록에 있던 항목 기준:

- `.env`, `.env.local` → 19행에서 여전히 무시 ✅
- `.env.bak.*` → `.env.bak.1234` 가 19행에서 여전히 무시 ✅ (`.example` 로 끝나지 않으므로 negation 사정권 밖)
- `.env.headroom.example` → 21행에서 재-무시 ✅ (§8-1)

즉 되살아난 건 정확히 "가려져 있으면 안 되는" 템플릿 3종뿐이다.

### 8-4. `v2-sandbox.env.example` — 초심 서술 그대로 유효

새 규칙에서도 `.env*` / `!.env*.example` **어느 쪽에도 매칭되지 않는다**(`check-ignore --no-index -v` 출력에 아예 등장하지 않음). `.env*` 는 경로 컴포넌트가 `.env` 로 *시작*해야 하는데 이 파일명은 `v2-sandbox.env.example` 이라 시작하지 않는다. 두 판 모두에서 정상 스테이징된다.

실제 워크트리에서도 추적 중인 3개가 negation 으로 해제됐음을 확인:
```
$ git check-ignore --no-index -v $(git ls-files | grep -E '(^|/)\.env')
.gitignore:20:!.env*.example	.env.example
.gitignore:20:!.env*.example	web/game/.env.example
.gitignore:20:!.env*.example	web/gateway/.env.example
```

### 8-5. `#513` 결론이 흔들리는가 — 아니다

`.gitignore` 변경이 스캔 집합을 바꾸는지 실측:

```
scannable_repo_files: 3590   (d8e098cc 측정치와 동일)
product-authority surface: 1069
.env.example family enumerated:
  ['.env.example', 'v2-sandbox.env.example', 'web/game/.env.example', 'web/gateway/.env.example']
```

집합 크기·구성 모두 `d8e098cc` 와 동일하다. 실행 코드는 `d8e098cc` 이후 한 줄도 바뀌지 않았으므로 차집합 0(§1-1)·뮤테이션 핀포인트(§2, §7-4)·비-git 계약(§3)·가드 자체 뮤테이션 검출(§7-3) 결론은 전부 그대로 유효하다. 저장소 전체 스위트도 green: `Ran 16 tests in 2.062s / OK`.

---

## UNKNOWN (근거를 만들지 못한 것)

- **오염 체크아웃 before 의 산출물이 정확히 0바이트였는지**: before 가 420초 예산에서 완주하지 않는 것만 확인했다(exit 124). 1100초 예산에서의 정확한 종료 코드/산출물 크기는 재현하지 않았다 — 재현 비용이 크고, 결론(미완주)에는 영향이 없다.
- **clean before/after 의 "findings 바이트 동일(`changedFiles` 제외)"**: 나는 `check_product_authority_policy()` 단위로만 비교했고(양쪽 findings=0, §6), `--format json` 전체 산출물의 바이트 비교는 하지 않았다. §1-1 의 집합 차집합 0 이 더 강한 근거라고 판단해 생략했다.
- **비-UTF8 파일명 실동작**: macOS 에서 재현 불가(§4). Linux 에서의 동작은 미검증.

---

## 검증에 쓴 명령 (재현용)

```bash
W=<meta>/worktrees/opensamguk/check-py-single-walk
S=<scratch>
# 정본 사본 (워크트리가 다른 에이전트에 의해 더럽혀져 있어서)
git -C "$W" archive 60836eaf tools/agent-system | tar -x -C $S/pristine
git -C "$W" show origin/main:tools/agent-system/check.py > $S/nongit/tools/agent-system/check.py

# 커버리지 차집합
python3 $S/covdiff.py "$W"
python3 $S/lost3.py   $S/pristine <polluted-checkout>

# 테스트 / 뮤테이션
cd "$W" && python3 -m unittest discover -s tools/agent-system/tests -p 'test_*.py'
# (뮤테이션은 $S/mut 사본에 sed 치환 후 재실행, 매회 원복)

# 성능
python3 $S/timeit.py $S/nongit   "$W"                  # before
python3 $S/timeit.py $S/pristine "$W"                  # after
timeout 420 python3 $S/timeit.py $S/nongit <polluted>  # exit 124

# gitignore 부작용
git -C "$W" ls-files -z | xargs -0 git -C "$W" check-ignore
cd <polluted> && git check-ignore -v .omo/ .omx/ .codegraph
```
