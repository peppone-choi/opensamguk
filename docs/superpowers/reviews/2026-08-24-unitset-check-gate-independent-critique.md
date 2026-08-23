# unitset --check CI 게이트 독립 비평 (f603f2c2)

Scope: `f603f2c2` on `work/opensamguk/ci-unitset-check-536` — wiring `tools/unitset/build_unitset.py --check` into the `agent-system` job of `.github/workflows/ci.yml`, verified in a fresh detached worktree at `f603f2c2` against base `origin/main` (`ad751195`)

Verdict: fix-required

검증 환경: 새 워크트리 `.../scratchpad/wt-unitset` (detached `f603f2c2`), Python 3.14.5, 읽기 전용. 커밋·푸시 없음.

---

## 결론 요약

- **A (게이트가 실제로 무는가): 통과.** 파생 훼손·authored 표 훼손·필드 삭제·순서 뒤집기·유닛 삭제·포맷 변경 전부 exit 1. 무수정 상태는 exit 0.
- **B (자기참조 구조): 게이트의 보호 범위가 좁다.** "authored 를 고치고 빌더를 다시 안 돌렸다" 만 잡는다. 통과하는 훼손 5종을 실제로 만들어 아래에 붙였다.
- **C (결정론): 통과.** `PYTHONHASHSEED` 6종에서 바이트 동일, 커밋본과도 동일.
- **D (CI 배선): 통과.** YAML 유효, 조건 분기·존재 검사 없음, push(main) + 모든 PR 에서 돈다.
- **E (커밋 위생): 파일 범위는 깨끗하나 diff 가 기존 스텝 하나를 조용히 지운다.** ← 이것이 fix-required 사유다.
- **F (회귀): 통과.** `--check` 0.14s.

---

## E. 블로커 — 기존 스텝을 말없이 삭제한다

`git show --stat f603f2c2` 는 `.github/workflows/ci.yml` 1 파일 `8 insertions(+), 2 deletions(-)`. `data/unitset/units.json`, `data/map/han-tiles.json` 모두 손대지 않았다 — 그 부분은 깨끗하다.

문제는 그 `2 deletions` 의 정체다.

```
-      - name: Verify Han route-node candidates
+      - name: Verify unitset build (data/unitset/units.json drift)
+        # ... (6줄 주석)
         if: '!cancelled()'
-        run: python3 tools/scenario/han_route_node_candidates.py --check
+        run: python3 tools/unitset/build_unitset.py --check
```

새 스텝을 **더한** 것이 아니라 `Verify Han route-node candidates` 스텝을 **덮어썼다**.

```
$ git grep -n "han_route_node_candidates" origin/main -- .github/
origin/main:.github/workflows/ci.yml:36:        run: python3 tools/scenario/han_route_node_candidates.py --check
$ grep -rn "han_route_node_candidates" .github/      # f603f2c2
NONE
```

그런데 커밋 메시지는 이렇게 적혀 있다:

> Added to the existing agent-system job (no new job), next to the other Han-map --check steps it already runs alongside.

**"next to" 가 아니라 "instead of" 다.** 커밋 메시지·주석·PR 본문 어디에도 스텝 하나가 사라졌다는 말이 없다. 이 저장소의 문제 계보 자체가 "게이트가 있는데 아무도 안 본다"(#521/#534)인데, 그 계보를 고치는 커밋이 같은 방식으로 게이트 하나를 소리 없이 없앤다.

### 다만 — 지워진 스텝은 원래 아무것도 안 하던 스텝이었다

이건 제출자에게 유리한 사실이라 명시한다. 지워진 스텝은 실행 커버리지가 **0** 이었다:

```
$ python3 tools/scenario/han_route_node_candidates.py --check > /tmp/c.out 2>&1
exit=0
$ wc -c /tmp/c.out
0 /tmp/c.out
$ python3 tools/scenario/han_route_node_candidates.py --this-flag-does-not-exist
exit=0
$ python3 -c "s=open('tools/scenario/han_route_node_candidates.py').read();
print('has __main__ :', '__main__' in s); print('has argparse :', 'argparse' in s); print('has sys.argv :', 'sys.argv' in s)"
has __main__ : False
has argparse : False
has sys.argv : False
```

`__main__` 블록도 argparse 도 없다. 이 스크립트는 **모듈을 import 하고 끝난다** — `--check` 든 `--this-flag-does-not-exist` 든 무조건 exit 0. `#521`/`#534` 와 같은 계보의 **세 번째 가짜 게이트**였고, PR #501(`59ec25eb`)에서 들어왔다.

그러니 실질 커버리지는 순증이다(가짜 게이트 1 제거 + 진짜 게이트 1 추가). fix-required 로 두는 이유는 커버리지 손실이 아니라 **기록이 사실과 다르기 때문**이다. 다음 사람이 `git log` 만 읽으면 스텝이 사라진 걸 절대 모른다.

**보너스 — 아직 살아 있는 같은 모양의 가짜 게이트:**

```
$ python3 -c "s=open('tools/scenario/han_route_node_selection.py').read();
print('__main__' if '__main__' in s else 'NO __main__')"
NO __main__
$ python3 tools/scenario/han_route_node_selection.py --check >/tmp/s.out 2>&1
selection --check exit=0 bytes=0
```

`Verify Han route-node selection` 스텝도 여전히 no-op 이다. 이번 PR 범위 밖이지만 #536 후속으로 티켓을 떼어야 한다. (`validate_han_route_node_selection.py` 와 `tools/agent-system/check.py` 는 둘 다 `__main__`+argparse 를 갖고 있어 진짜다.)

### 최소 수정

둘 중 하나. (1) `Verify Han route-node candidates` 스텝을 되살리고 unitset 스텝을 **추가**한다. 또는 (2) 의도적 제거라면 그렇게 적는다 — 커밋 메시지에 "이 스텝은 argparse 도 `__main__` 도 없어 항상 exit 0 인 no-op 이라 함께 제거한다" 와 근거를 넣고, `han_route_node_selection.py --check` 도 같은 사유로 정리하거나 후속 이슈를 남긴다. (2)를 권한다 — 가짜 게이트를 되살릴 이유가 없다. 어느 쪽이든 **"조용히"** 는 안 된다.

---

## A. 게이트가 실제로 무는가 — 17+5 종 훼손 실측

베이스라인:

```
$ python3 tools/unitset/build_unitset.py --check
data/unitset/units.json — 최신
exit=0     (0.139s total)
$ git status --short
(빈 출력)
```

각 훼손은 스크립트로 넣고 **즉시 원복**했다. 실제 출력:

| # | 훼손 | exit | 출력 꼬리 |
|---|---|---|---|
| A1 | 파생 `attack` +1 (제출자가 한 것) | **1** | `... 이 최신이 아니다 — python3 tools/unitset/build_unitset.py 로 다시 만들어라` |
| A2 | **authored** `tables.weapons[환수도].attack` +1 | **1** | 같음 |
| A3 | **authored** `tables.armors[의복].defence` +1 | **1** | 같음 |
| A4 | **authored** `tables.shields[*].avoid` +1 | **1** | 같음 |
| A6 | **authored** `composition.weapon` 교체 | **1** | 같음 |
| A7 | 파생 필드 `attack` **삭제** | **1** | 같음 |
| A8 | authored 필드 `tier` **삭제** | **1** | `KeyError: 'tier'` |
| A9 | 유닛 **추가**(대역 밖 id) | **1** | `AssertionError: han 세트 id 대역 이탈: [12000]` |
| A11 | 한 유닛 안 **키 순서 뒤집기** | **1** | 드리프트 메시지 |
| A12 | 미지 필드 추가 | **1** | `KEYS 에 없는 필드: ['zzzBogus'] — 오타이거나 KEYS 에 추가해야 한다` |
| A15 | `_meta.counts.units=1` | **1** | 드리프트 메시지 |
| A17 | 의미 동일 재포맷(indent=2) | **1** | 드리프트 메시지 |
| B1 | **authored** `tables.tiers["1"].ko` 개명(**쓰이는** 등급) | **1** | 드리프트 메시지 |
| B2 | han 유닛 하나 **통째 삭제** | **1** | 드리프트 메시지 |
| B4 | **authored** `requires` → `{"region":"南中"}` 로 재작성 | **1** | 드리프트 메시지 |
| B5 | 두 유닛의 `attack` **맞바꾸기**(합계 불변) | **1** | 드리프트 메시지 |

**질문에 대한 직답:**
- 파생 필드 훼손 → RED. ✅
- **authored 훼손(무기·갑옷·방패·등급 표, 명부) → RED.** ✅ 무기표 한 칸만 건드려도 파생이 달라져 잡힌다. 이게 이 게이트의 실제 값어치다.
- 필드 삭제 → RED(파생은 드리프트, authored 는 `KeyError` 트레이스백 — 둘 다 exit 1 이지만 후자는 메시지가 사납다).
- 항목 추가 → RED(id 대역 assert).
- 순서 뒤집기 → 유닛 **안** 키 순서는 RED, 유닛 **간** 순서는 GREEN(아래 B 참조).
- 무수정 GREEN → 예. ✅

## B. 자기참조 구조 — 통과해버리는 훼손 5종

`OUT` 과 `UNITSET` 은 같은 경로(`build_unitset.py:40,45`)다. `--check` 는 `DOC = json.loads(UNITSET.read_text())` 로 자기 자신을 읽고, authored 필드로 파생 필드를 다시 만들어 **전체 문서를 재직렬화한 텍스트**를 파일 원문과 비교한다(`:268-273`).

따라서 검사 대상은 **딱 하나: "이 파일 안의 authored 필드와 파생 필드가 서로 맞는가"** 다. 이건 표준적인 "빌더 다시 안 돌렸다" 게이트이고, 그 이상은 아니다.

**고정점 함정은 존재한다. 만들어 보였다** — 아래 5종은 전부 exit 0 (GREEN):

```
[A5  tables.tiers["0"].ko 개명(han 유닛이 안 쓰는 등급)]      exit=0  → "최신"
[A10 crewTypes 배열 전체 순서 뒤집기]                        exit=0  → "최신"
[A13 evidence.cite 를 "COMPLETELY FABRICATED" 로 조작]        exit=0  → "최신"
[A14 che 세트 행의 attack 을 99999 로]                        exit=0  → "최신"
[A16 아무도 안 쓰는 가짜 무기 행을 tables.weapons 에 추가]     exit=0  → "최신"
```

- **A10 이 가장 뾰족하다.** 명부 134종의 순서를 통째로 뒤집어도 GREEN 이다. 빌더가 입력 순서를 그대로 출력하므로 어떤 순서든 자기 자신의 고정점이다. 정렬 불변식(id 오름차순 등)은 전혀 강제되지 않는다.
- **A14**: `che` 세트 행은 `build()` 가 `derive()` 를 안 태운다(`:236`). 코틀린 사본이라 의도된 것이지만, 이 게이트가 `che` 행에 주는 보호는 **0** 이다.
- **A13/A5/A16**: 파생에 안 쓰이는 authored 값(`evidence.cite`, 안 쓰는 등급/재료 행)은 자유롭게 조작 가능하다. `B3`(유닛 `name` 개명)도 GREEN 이었다.

**보호 경계 — 과대평가하지 말 것:**

| 잡는다 | 못 잡는다 |
|---|---|
| authored 를 고치고 `build_unitset.py` 를 다시 안 돌린 상태 | **authored 데이터 자체가 틀린 것** |
| 파생 수치를 손으로 만진 것 | 명부 순서 |
| 파생 필드 추가/삭제/개명, 미지 필드, 포맷 드리프트 | `che` 세트 행 전체 |
| id 중복·누락·세트 대역 이탈, `_meta.counts` | 파생에 안 쓰이는 authored 값(`evidence.cite`, `name`, 미사용 표 행) |
| `materialCeiling` 금제 위반, 방패 불가 무기 | 사료 인용의 진위, 균형의 타당성 |

**제출자 요청대로 직답: "authored 데이터 자체가 틀린 건 못 잡는다" — 맞다.** 더 정확히는, **아무리 틀린 authored 값이라도 `build_unitset.py` 를 한 번 돌리는 순간 파일이 자기정합 상태로 굳어 영구히 GREEN 이 된다.** 이 게이트는 데이터의 정확성이 아니라 **재생성 누락**만 막는다. 그 자체로 가치가 있고(#528 계보의 실수를 잡는다) 이 PR 이 잘못한 건 아니지만, "unitset 이 CI 로 검증된다" 로 읽히면 안 된다.

## C. 결정론 — 통과

`PYTHONHASHSEED` 6종에서 빌드(쓰기) 후 sha256 앞 16자:

```
seed=0      sha=d723d3cc38220ed2   --check exit 0
seed=1      sha=d723d3cc38220ed2   --check exit 0
seed=2      sha=d723d3cc38220ed2   --check exit 0
seed=42     sha=d723d3cc38220ed2   --check exit 0
seed=12345  sha=d723d3cc38220ed2   --check exit 0
seed=random sha=d723d3cc38220ed2   --check exit 0

$ git show f603f2c2:data/unitset/units.json | shasum -a 256 | cut -c1-16
d723d3cc38220ed2
$ git status --short
(빈 출력)
```

6회 빌드 후에도 `git status` 가 비었다 — 커밋본이 이미 고정점이다. 코드 상으로도 set 순회 결과는 전부 `sorted()` 를 거치고(`:197`, `:214-215`, `:237`), dict 는 삽입 순서 보장이며, 반올림은 `Decimal(ROUND_HALF_UP)` 로 float 표현에 의존하지 않는다(`:61-65`).

**UNKNOWN**: 로컬은 Python 3.14.5, CI 러너는 `ubuntu-latest` 기본 python3(현재 3.12 계열)다. 다른 마이너 버전에서 돌려보지 못했다 — 이 환경에 3.14 외 인터프리터가 없다. `json.dumps` 포맷과 `Decimal` 은 버전 간 안정적이라 위험은 낮다고 보지만 **실측하지 않았다.** 이건 실제 CI 첫 실행이 답할 것이다.

## D. CI 배선 — 통과

```
$ python3 -c "import yaml,json; d=yaml.safe_load(open('.github/workflows/ci.yml')); ..."
YAML OK. jobs: ['agent-system', 'jvm', 'web']
triggers: {"push": {"branches": ["main"]}, "pull_request": null}
 - None                                                    | if: None
 - 'Check provider-agnostic agent working system'          | if: None
 - 'Verify Han map data contract tests'                    | if: '!cancelled()'
 - 'Verify scenario data contract tests'                   | if: '!cancelled()'
 - 'Verify agent-system tool tests'                        | if: '!cancelled()'
 - 'Verify unitset build (data/unitset/units.json drift)'  | if: '!cancelled()'
 - 'Verify Han route-node selection'                       | if: '!cancelled()'
 - 'Verify Han route-node selection validator'             | if: '!cancelled()'
 - 'Verify v2 sandbox compose contract'                    | if: '!cancelled()'
 - 'Verify JWT rollout contract'                           | if: '!cancelled()'
 - 'Verify deploy service inventory contract'              | if: '!cancelled()'
 - 'Verify local compose service graph'                    | if: '!cancelled()'
```

- **무조건 도는가: 예.** `if` 는 `!cancelled()` 뿐이다. 존재 검사 없음, skip 분기 없음, `continue-on-error` 없음(job 전체에 하나도 없다). #534 의 gitignore 장벽 같은 우회로가 없는 것도 맞다 — 입력이 tracked 파일 하나뿐이다.
- **`!cancelled()` 의도 여부: 의도된 것이 맞다.** 바로 위 `Check provider-agnostic agent working system` 스텝 위에 2026-08-24 자 주석이 이유를 적어 두었다(check.py 가 죽으면 뒤 스텝이 전부 조용히 skip 돼 #517/#519 가 필요로 한 증거가 사라졌다). 새 스텝은 그 관례를 따랐을 뿐이다.
- **실패 집계: 정상.** `!cancelled()` 는 "앞이 실패해도 이 스텝은 돈다" 이지 "실패를 무시한다" 가 아니다. `continue-on-error` 가 없으므로 이 스텝이 exit 1 이면 job 은 실패한다. 앞 스텝이 이미 실패한 경우 job 은 어차피 실패 상태이고, 이 배선은 그 위에 "unitset 은 어땠나" 정보를 더한다.
- **트리거: `push`(main) + `pull_request`(전체).** PR 에서 돈다 — 게이트 조건 충족.

## F. 회귀 — 통과

```
$ time python3 tools/unitset/build_unitset.py --check
data/unitset/units.json — 최신
0.10s user 0.03s system 94% cpu  0.139 total
```

job `timeout-minutes: 5` 에 0.14s 추가. 무시할 수준. 다른 스텝과 상태를 공유하지 않고 파일을 쓰지 않으므로(`--check` 경로는 `write_text` 를 타지 않는다, `:269-273`) 뒤 스텝을 오염시키지 않는다.

## 정리 확인

```
$ git status --short
(빈 출력)
```

모든 실험 훼손은 원복됐다. 워크트리에 커밋·푸시 없음. 이 리뷰 파일은 uncommitted 로 남긴다.
