# unitset --check CI 게이트 독립 비평 — 통합본 (f603f2c2 + cf20c513)

Scope: `f603f2c2` and `cf20c513` on `work/opensamguk/ci-unitset-check-536` — wiring `tools/unitset/build_unitset.py --check` into the `agent-system` job of `.github/workflows/ci.yml`, and the follow-up that drops the two no-op steps `f603f2c2` had overwritten silently and records the gate ceiling, both verified in a fresh detached worktree against base `origin/main` (`ad751195`)

Verdict: cleared

## 판정 이력 — 지우지 않고 남긴다

| 회차 | 대상 | 판정 | 사유 |
|---|---|---|---|
| 1차 | `f603f2c2` | **fix-required** | E — 기존 CI 스텝 `Verify Han route-node candidates` 를 말없이 덮어쓰고, 커밋 메시지는 "next to the other steps" 라고 적어 기록이 사실과 달랐다 |
| 2차 | `cf20c513` | **cleared** | 제출자가 (2) 의도적 제거로 기록 안을 택해 해소. 스텝 2개 제거 사실·no-op 근거·계보 맥락이 커밋 메시지에 전부 들어갔고, 남은 게이트 둘은 RED 탐침으로 진짜임을 확인했다 |

기능 검증(A 파생·authored 훼손 RED, B 자기참조 고정점, C 결정론, D 배선, F 성능)은 1차에서 통과했고 2차에서 뒤집히지 않았다. 아래에 두 회차를 **그대로** 싣는다 — 무엇이 왜 차단됐고 어떻게 풀렸는지가 이 문서의 값이다.

**부수 관찰(별도 티켓 #542):** `tools/scenario/materialize_han_route_node_selection.py --check` 는 `exit=0, "han route-node selection and migration: no drift"` 를 내는 **실제로 도는 드리프트 게이트인데 CI 어디에도 배선돼 있지 않다.** "게이트는 있는데 아무도 안 부른다" 계보의 다섯 번째다. 이 PR 에서 고치지 않는다.

---

## 1차 판정 — `f603f2c2` (판정: fix-required)

검증 환경: 새 워크트리 `.../scratchpad/wt-unitset` (detached `f603f2c2`), Python 3.14.5, 읽기 전용. 커밋·푸시 없음.

---

### 결론 요약

- **A (게이트가 실제로 무는가): 통과.** 파생 훼손·authored 표 훼손·필드 삭제·순서 뒤집기·유닛 삭제·포맷 변경 전부 exit 1. 무수정 상태는 exit 0.
- **B (자기참조 구조): 게이트의 보호 범위가 좁다.** "authored 를 고치고 빌더를 다시 안 돌렸다" 만 잡는다. 통과하는 훼손 5종을 실제로 만들어 아래에 붙였다.
- **C (결정론): 통과.** `PYTHONHASHSEED` 6종에서 바이트 동일, 커밋본과도 동일.
- **D (CI 배선): 통과.** YAML 유효, 조건 분기·존재 검사 없음, push(main) + 모든 PR 에서 돈다.
- **E (커밋 위생): 파일 범위는 깨끗하나 diff 가 기존 스텝 하나를 조용히 지운다.** ← 이것이 fix-required 사유다.
- **F (회귀): 통과.** `--check` 0.14s.

---

### E. 블로커 — 기존 스텝을 말없이 삭제한다

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

### A. 게이트가 실제로 무는가 — 17+5 종 훼손 실측

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

### B. 자기참조 구조 — 통과해버리는 훼손 5종

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

### C. 결정론 — 통과

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

### D. CI 배선 — 통과

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

### F. 회귀 — 통과

```
$ time python3 tools/unitset/build_unitset.py --check
data/unitset/units.json — 최신
0.10s user 0.03s system 94% cpu  0.139 total
```

job `timeout-minutes: 5` 에 0.14s 추가. 무시할 수준. 다른 스텝과 상태를 공유하지 않고 파일을 쓰지 않으므로(`--check` 경로는 `write_text` 를 타지 않는다, `:269-273`) 뒤 스텝을 오염시키지 않는다.

### 정리 확인

```
$ git status --short
(빈 출력)
```

모든 실험 훼손은 원복됐다. 워크트리에 커밋·푸시 없음. 이 리뷰 파일은 uncommitted 로 남긴다.

---

## 2차 재심 — `cf20c513` (판정: cleared)

`f603f2c2` 의 A~D·F 는 선행 비평(`fa13d021`)에서 이미 통과 판정했다. 여기서는 `cf20c513` 만 본다. 새 워크트리 detached `cf20c513`(및 브랜치 팁 `fa13d021`), Python 3.14.5, 읽기 전용.

---

### ⚠ 프로토콜 결함 — `fix-required` 는 해소 경로가 없다 (이 통합본으로 해소됨)

이 통합본을 내기 전, 브랜치 팁에서 `agent-system` job **1번 스텝**이 실패했다.

```
$ git log --oneline -1
fa13d021 docs: add independent critique of unitset check-gate (fix-required, addressed in cf20c513)
$ python3 tools/agent-system/check.py --strict --base origin/main
exit=1
## Findings
- **ERROR cross-agent-critique**: Unresolved Verdict: fix-required blocks completion:
  docs/superpowers/reviews/2026-08-24-unitset-check-gate-independent-critique.md
```

`check.py:1300` 이 원인이다. 바뀐 파일 중 `docs/superpowers/reviews/*.md` 를 순회하다가 **`Verdict: fix-required` 를 만나면 그 자리에서 `return`** 한다:

```python
if verdicts[0] == "fix-required":
    return [Finding("error", "cross-agent-critique", f"Unresolved Verdict: fix-required blocks completion: {rel}")]
```

즉 **나중 파일이 `cleared` 여도 소용없다.** 실측:

```
$ (이 재심 파일을 Verdict: cleared 로 추가한 뒤)
$ python3 tools/agent-system/check.py --strict --base origin/main
exit=1   ← 여전히 fa13d021 파일이 막는다
```

막힌 것을 푸는 경로도 실측했다 — `fa13d021` 파일의 `Verdict:` 줄만 `cleared` 로 바꾸면 통과한다:

```
$ python3 - <<'PY'   # 임시로 fa13d021 파일의 Verdict 줄만 교체
...replace("\nVerdict: fix-required\n", "\nVerdict: cleared\n", 1)
PY
$ python3 tools/agent-system/check.py --strict --base origin/main
exit=0
## Findings
No findings.
$ git checkout -- <그 파일>     # 즉시 원복함
```

**해소 방법과 그 근거.** `check.py:1300` 의 설계 자체는 옳다 — 미해소 `fix-required` 를 안고 머지하면 안 된다. 결함은 워크플로가 아니라 **"비평문을 한 글자도 고치지 말고 커밋해라"** 는 운영 지시에 있었다. `cleared` 일 때는 맞는 규칙이지만 `fix-required` 일 때는 저장소를 영구히 빨갛게 만드는 지시가 된다.

제출자가 verdict 줄을 고치게 하는 것은 답이 아니다 — "제출자가 자기 판정을 고친다" 가 되어 독립성이 무너진다. 판정은 비평자의 것이므로 **비평자가 이 통합본으로 다시 낸다.** 1차 `fix-required` 는 위 판정 이력 표와 1차 절에 그대로 보존되고, 앵커 줄만 2차 결과인 `cleared` 를 가리킨다.

이 통합본이 들어간 상태에서 게이트가 실제로 풀리는 것을 확인했다:

```
$ python3 tools/agent-system/check.py --strict --base origin/main
exit=0
## Findings

No findings.
```

즉 `cf20c513` 은 흠이 없고, 이 문서 하나가 PR 을 막던 마지막 항목이었다.

---

### 1. 커밋 메시지가 이제 사실을 말하나 — 예

차단 사유였던 항목이다. `git log` 만 읽고도 무슨 일이 있었는지 알 수 있는지 확인했다. `cf20c513` 메시지에 다음이 전부 들어 있다.

- **f603f2c2 가 무엇을 잘못했는지 자백한다.** "replaced the ... step with the new unitset --check step, but its commit message said 'next to the other steps' without mentioning a step was removed -- **the record didn't match reality**."
- **스텝 2개가 제거됐다는 사실**과 **각각의 근거.** 실행 출력 4개(양쪽 스크립트의 `--check`, 아무 플래그나 먹는다는 증명, grep) 를 붙였다.
- **#521/#534 계보의 3·4번째**라는 맥락. "a third and fourth instance of the 'gate exists, nobody looks' shape (#521, #534)."
- **의도적 제거임을 명시.** "a real, intentional cleanup, not an accidental side effect."
- **남긴 것과 그 근거.** "validate_han_route_node_selection.py (kept, still wired) and tools/agent-system/check.py both have argparse + `__main__` -- confirmed real, left untouched."

제목(`drop 2 no-op CI steps overwritten silently, add gate ceiling note`)만 봐도 스텝이 사라졌다는 걸 안다. **차단 사유는 해소됐다.**

### 2. 제출자가 근거를 스스로 재현했나 — 명령은 전부 유효, 출처는 UNKNOWN

먼저 **인용된 4개 명령이 실제로 그 출력을 내는지 직접 돌렸다.** 전부 맞다:

```
$ python3 tools/scenario/han_route_node_candidates.py --check      → exit 0, 0 bytes
$ python3 tools/scenario/han_route_node_candidates.py --this-flag-does-not-exist → exit 0
$ python3 tools/scenario/han_route_node_selection.py --check       → exit 0, 0 bytes
$ /usr/bin/grep -c '__main__\|argparse\|sys.argv' tools/scenario/han_route_node_candidates.py tools/scenario/han_route_node_selection.py
tools/scenario/han_route_node_candidates.py:0
tools/scenario/han_route_node_selection.py:0
```

grep 이 진짜 탐침인지도 대조군으로 확인했다 — 같은 grep 을 `argparse`/`__main__` 이 실제로 있는 파일에 돌리면 잡힌다:

```
$ grep -n '__main__\|argparse\|sys.argv' tools/scenario/validate_han_route_node_selection.py
12:import argparse
1964:    parser = argparse.ArgumentParser()
2009:if __name__ == "__main__":
```

즉 "no matches" 는 진짜 없다는 뜻이지, grep 이 헛도는 게 아니다.

**출처 구분은 안 된다 — UNKNOWN.** `--this-flag-does-not-exist` 는 선행 비평문(`fa13d021`)에 내가 지어 넣은 문자열 그대로다. 우연히 같은 플래그명을 고를 확률은 낮으니 **적어도 일부는 내 비평문에서 옮긴 것**으로 보인다. 반면 `han_route_node_selection.py --check` 실행과 두 파일을 한 번에 건 combined grep 은 내 비평문에 그 형태로 없다(나는 selection 쪽을 python 문자열 검사로 확인했다) — 그 부분은 새로 돌린 것으로 보인다. 커밋 산출물만으로는 어디까지가 재현이고 어디까지가 전사인지 **가릴 수 없다.**

다만 그것이 이번 판정을 바꾸지는 않는다. **인용된 출력이 전부 사실이고 내가 독립적으로 재현했다.** 지어낸 명령·출력은 없다.

### 3. 진짜 게이트를 실수로 지우지 않았나 — 지우지 않았다. 남은 둘 다 실제로 돈다

`validate_han_route_node_selection.py` 는 **인자 없이도 검증한다.** argparse 인자가 전부 `default=` 를 가져(`:1965-1973`) 무인자 호출이 곧 기본 매니페스트 검증이다. 출력이 있다:

```
$ python3 tools/scenario/validate_han_route_node_selection.py
exit=0  bytes=316
han route-node selection approved production manifest (curated provenance snapshot validated;
live corpus refresh not claimed): approved=780 scenarios=15
selectionSha256=a01711930e6f1162cb36718103add1d0feedbfd65d00f8b7bf53e88fad4ee038
migrationSha256=d7ed5ae6ae1cefcd23bc8ad8ca0c24a09d52bd13c000f6d6dcda0214c7c0a528
```

exit 0 만 보고 넘기지 않았다 — **RED 탐침도 넣었다.** `data/curated/han/route-node-selection-v1.json` 의 `routeNodes` 에서 원소 하나를 지웠다:

```
RED probe exit=2
han route-node selection validation failed: replacementDecisionSet must exact-match the reviewed assignment set
$ (원복)
restored exit=0
```

진짜 게이트다. `tools/agent-system/check.py` 도 진짜다 — 위 ⚠절이 그 증거다(exit 1 + 구체적 Finding). **no-op 을 지우는 커밋이 no-op 을 남기는 우스운 일은 일어나지 않았다.**

**다만 인접 관찰 하나 — 지워진 두 모듈에는 진짜 CLI 껍데기가 따로 있다.**

```
$ git grep -n "han_route_node_candidates\|han_route_node_selection" -- tools/
tools/scenario/build_han_route_node_candidates.py:23:  from han_route_node_candidates import (
tools/scenario/materialize_han_route_node_selection.py:22:  from tools.scenario.han_route_node_selection import (
```

지워진 둘은 **라이브러리 모듈**이고, 이것들을 import 하는 `build_han_route_node_candidates.py` / `materialize_han_route_node_selection.py` 가 진짜 CLI 다. 후자를 돌려보면:

```
$ python3 tools/scenario/materialize_han_route_node_selection.py --check
exit=0
han route-node selection and migration: no drift
$ python3 tools/scenario/build_han_route_node_candidates.py --check
exit=2
han route-node candidate build failed: --check requires --output
```

**`materialize_han_route_node_selection.py --check` 는 실제로 도는 드리프트 게이트인데 CI 어디에도 배선돼 있지 않다.** 원래 ci.yml 이 부르려던 것이 이쪽이었을 개연성이 크다 — 라이브러리 파일명(`han_route_node_selection.py`)과 CLI 파일명(`materialize_han_route_node_selection.py`)이 한 접두사 차이다. 계보의 **다섯 번째**인 셈이다.

차단 사유로 올리지는 않는다. (a) `cf20c513` 은 "no-op 을 지운다" 는 자기 주장 범위를 정확히 지켰고, (b) 커버리지가 완전히 비지도 않는다 — `Verify scenario data contract tests` 스텝이 그 모듈들의 테스트를 돈다:

```
$ python3 -m unittest discover -s tools/scenario/tests -p 'test_*.py'
Ran 252 tests in 53.874s
OK (skipped=1)
```

(`test_han_route_node_materializer.py`, `test_han_route_node_selection.py`, `test_han_route_node_validator.py` 가 이 안에 있다.) **#536 후속 티켓 감이다 — 이 PR 에서 고치라는 뜻이 아니다.**

### 4. `ci.yml` 19~21행 주석 — `check.py` 는 실패하고 있었다. 다만 사유가 다르다

주석이 말하는 2026-08-24 자 사건("check.py failing at the step above silently skipped everything after it")은 과거 사건 기록이고, `!cancelled()` 를 넣은 이유다. 그런데 **재심 시점에도 `check.py` 는 실패하고 있었다** — 위 ⚠절의 그 실패다. 원인은 `cf20c513` 이 아니라 `fa13d021` 이 커밋한 `Verdict: fix-required` 산출물이며, 그 줄을 `cleared` 로 바꾸면 `exit=0 / No findings` 가 된다(실측). 깨끗한 `cf20c513` 트리(내 untracked 리뷰 파일을 치운 상태)에서는 다른 사유로 실패한다:

```
$ python3 tools/agent-system/check.py --strict --base origin/main   # cf20c513, 리뷰 파일 없음
exit=1
- **ERROR cross-agent-critique**: Strict non-trivial changes require a PR-visible
  docs/superpowers/reviews/*.md critique artifact.
```

즉 `check.py` 자체는 정상 동작 중이고, 비평 산출물이 붙기 전에는 "붙여라", 붙었는데 `fix-required` 면 "해소해라" 라고 정확히 말한다. **워크플로 결함이 아니다. 이 PR 범위 밖의 새 작업도 아니다** — 필요한 건 위 ⚠절의 산출물 정리 한 번뿐이다.

### 5. Ceiling 주석 — 정확하다. 과대주장 아니고, 오히려 안전한 쪽으로 축소돼 있다

추가된 6줄:

```yaml
# Ceiling: it only catches a missed regen (the
# derived fields not matching the authored ones) -- it re-derives
# from whatever is currently authored in units.json, so a wrong
# authored value that's been regenerated through this script reads
# as GREEN forever. This is not a correctness gate on the data,
# only a "did you forget to rerun the generator" gate.
```

선행 비평 B 절에서 실증한 고정점 함정 5종에 비추어 판정한다.

- **"re-derives from whatever is currently authored" → 정확.** 자기참조(`OUT`≡`UNITSET`, `build_unitset.py:40,45`)의 핵심을 정확히 짚었다.
- **"a wrong authored value that's been regenerated ... reads as GREEN forever" → 정확.** 내가 B 절에서 내린 결론과 같은 문장이다.
- **"not a correctness gate on the data, only a 'did you forget to rerun the generator' gate" → 정확하고, 이것이 가장 중요한 한 줄이다.** "unitset is CI-verified" 로 오독되는 것을 정확히 막는다.

**과대주장은 없다.** 주석이 말하지 않는 사각지대는 세 가지 더 있다 — `che` 세트 행은 `derive()` 를 아예 안 타서(`build_unitset.py:236`) 보호가 0, `crewTypes` 배열 **순서**는 어떤 순서든 자기 고정점이라 통째로 뒤집어도 GREEN, 파생에 안 쓰이는 authored 값(`evidence.cite`, `name`, 미사용 표 행)도 GREEN. 다만 이것들은 주석이 그은 선의 **안쪽**이다 — 주석은 "데이터 정확성 게이트가 아니다" 라고 이미 더 넓게 말했으므로, 빠진 항목이 주석을 거짓으로 만들지 않는다. **축소 서술이지 과대 서술이 아니다.** 굳이 한 줄 더 넣는다면 `che` 세트가 아예 유도 대상이 아니라는 점 정도인데, 없다고 문제 되지 않는다.

### 6. 커밋 위생 — 통과

```
$ git show --stat cf20c513
 .github/workflows/ci.yml | 10 ++++++----
 1 file changed, 6 insertions(+), 4 deletions(-)
```

`data/` 아래 무변경. `data/unitset/units.json`, `data/map/han-tiles.json`(#536 재생성 금지) 둘 다 안 건드렸다.

YAML 유효하고 남은 스텝은 셋 다 무조건 실행이다:

```
YAML OK; jobs: ['agent-system', 'jvm', 'web']
triggers: {"push": {"branches": ["main"]}, "pull_request": null}
  - 'Check provider-agnostic agent working system'          | if: None       | coe: None
  - 'Verify Han map data contract tests'                    | if: '!cancelled()' | coe: None
  - 'Verify scenario data contract tests'                   | if: '!cancelled()' | coe: None
  - 'Verify agent-system tool tests'                        | if: '!cancelled()' | coe: None
  - 'Verify unitset build (data/unitset/units.json drift)'  | if: '!cancelled()' | coe: None
  - 'Verify Han route-node selection validator'             | if: '!cancelled()' | coe: None
  - 'Verify v2 sandbox compose contract'                    | if: '!cancelled()' | coe: None
  - 'Verify JWT rollout contract'                           | if: '!cancelled()' | coe: None
  - 'Verify deploy service inventory contract'              | if: '!cancelled()' | coe: None
  - 'Verify local compose service graph'                    | if: '!cancelled()' | coe: None
```

지워진 두 스텝(`Verify Han route-node candidates`, `Verify Han route-node selection`)은 사라졌고, 팀리드가 짚은 세 줄(19/47/50)이 그대로다. **의존성 파손 없음:** `agent-system`/`jvm`/`web` 모두 `needs: None` 이라 job 간 의존이 없고, 다른 workflow 5개(`deploy.yml`, `predeploy-go-check.yml`, `promote-game-server.yml`, `reset-game-server.yml`, `daemon-health-alert.yml`) 어디에도 지워진 스크립트 참조가 없다. 스크립트 파일 자체는 남아 있어(라이브러리로 import 된다) dangling import 도 없다.

### 7. 회귀 — 통과

`cf20c513` 트리에서 양방향 재확인:

```
$ python3 tools/unitset/build_unitset.py --check
data/unitset/units.json — 최신
exit=0

$ (한 han 유닛의 파생 attack 을 +1)
$ python3 tools/unitset/build_unitset.py --check
data/unitset/units.json 이 최신이 아니다 — python3 tools/unitset/build_unitset.py 로 다시 만들어라
exit=1

$ git checkout -- data/unitset/units.json
$ python3 tools/unitset/build_unitset.py --check
restored exit=0
```

### 정리 확인

```
$ git status --short
(빈 출력 — 이 재심 파일만 untracked)
```

RED 탐침으로 건드린 `data/curated/han/route-node-selection-v1.json` 과 `data/unitset/units.json` 은 즉시 원복했고, `fa13d021` 비평문의 Verdict 줄을 임시로 바꾼 것도 `git checkout --` 로 되돌렸다. 커밋·푸시 없음. 이 파일은 uncommitted 로 둔다.
