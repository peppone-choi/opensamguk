# 독립 리뷰 — `ci-agent-system-masking` (agent-system 잡의 downstream 실패 은폐 수정)

Scope: `.github/workflows/ci.yml` 의 `agent-system` 잡 (커밋 `5295bee1`, `9e9cbab3`(#517) 위로 리베이스됨; `5448ce1d`→`1f62f5f9` 는 deliberate-break/revert 쌍으로 `tools/agent-system/check.py` 순변경 0). 리뷰 레인은 작성 레인 밖이며, 아래 판단은 이 세션에서 `git diff origin/main`, `git diff 5295bee1 HEAD -- tools/agent-system/check.py`, PyYAML 파싱으로 재확인한 것이다.

## 0. 재검증 배경

1차 리뷰에서 HIGH 차단 결함(브랜치가 `origin/main` 보다 1커밋 뒤, `#517`이 추가한 `tools/agent-system/tests` 유닛테스트 스텝이 diff상 삭제되는 것으로 나타남)을 지적했다. 브랜치를 현재 `origin/main`(`9e9cbab3` 포함) 위로 리베이스하고, 충돌 지점에서 분할-스텝/`!cancelled()` 구조를 유지하면서 "Verify agent-system tool tests" 스텝을 `Verify scenario data contract tests` 바로 뒤(병합된 main과 동일한 상대 위치)에 복원했다는 보고를 받아 재검증했다.

## 1. 실측 사실 (재확인)

| 확인 | 결과 |
|---|---|
| `git status --short --branch` | `ahead 4, behind 3` (origin 쪽 트래킹 브랜치 기준 — 로컬 리베이스가 아직 push 안 됨. `origin/main` 기준으로는 최신) |
| `git diff origin/main -- .github/workflows/ci.yml` | "Verify agent-system tool tests" 스텝이 **복원됨** — `Verify scenario data contract tests` 직후, `if: '!cancelled()'`, `python3 -m unittest discover -s tools/agent-system/tests -p 'test_*.py'` 그대로 |
| `git diff 5295bee1 HEAD -- tools/agent-system/check.py` | **빈 diff** — deliberate-break(`5448ce1d`)/revert(`1f62f5f9`) 쌍이 순변경 0 을 실측으로 확인 |
| PyYAML 파싱 | 문법 유효 |
| `if:` 배치 | 9개 verify 스텝 전부 `!cancelled()` (다른 값 없음, `always()` 아님) — `checkout`과 첫 `check.py` 스텝만 조건 없음(정상) |
| 스텝 순서 | `check.py` → 6개 unittest/scenario 스텝(신규 이름 부여, merge된 main과 순서 동일) → 기존 4개 스텝(compose/JWT/inventory/compose-graph) — 재정렬 없음 |
| `continue-on-error` | 잡 전체에 없음 — 실패가 잡 실패로 그대로 올라온다 |

## 2. 이전 차단 결함 — 해소 확인

**[해소됨] "Verify agent-system tool tests" 누락**

`git diff origin/main -- .github/workflows/ci.yml` 재실행 결과, 삭제 라인(`-`)에 더 이상 `tools/agent-system/tests` 유닛테스트가 나타나지 않는다. 대신 diff는 순수 리팩터(단일 명령 블록 → 9개 이름 붙은 `if: '!cancelled()'` 스텝 분해, `Verify agent-system tool tests` 신규 추가 포함)로만 구성된다. 이전에 지적한 실패 시나리오(리베이스 없이 진행 시 3-way 충돌 또는 조용한 유닛테스트 신호 소실)는 더 이상 해당하지 않는다.

## 3. 요청받은 항목별 판정 (재확인)

- **스텝 순서 / 누락·중복**: `origin/main` 대비로도 완전. 6개 명령 → 6개 이름 붙은 스텝(신규 "Verify agent-system tool tests" 포함), 순서·인자·따옴표(`-p 'test_*.py'`) 모두 1:1 동일. 뒤따르는 4개 기존 스텝도 순서·내용 그대로이고 `if:` 만 추가됐다.
- **`if:` 배치**: 정확. YAML 매핑이라 `if:`/`run:` 키 순서는 무의미하고, `'!cancelled()'` 를 따옴표로 감싼 것은 필수다(`!` 로 시작하는 평문 스칼라는 YAML 태그 지시자로 파싱됨). 파서로 확인했다.
- **`!cancelled()` vs `always()`**: `!cancelled()` 가 옳은 선택이다. 둘 다 기본 `success()` 를 무효화해 앞 스텝 실패 후에도 실행되지만, `always()` 는 취소된 잡에서도 남은 스텝을 계속 굴려 러너 시간을 먹고 취소를 무의미하게 만든다. 참고로 같은 파일 `jvm` 잡의 `Surface test results` 는 여전히 `always()` 인데, 결과 수집 스텝이라 치명적이진 않지만 일관성 차원에서 언젠가 맞추면 좋다. (LOW, 이 PR 범위 밖)
- **쉘 의미 보존**: 보존된다. 원래 블록은 `bash -e` 한 프로세스, 지금은 스텝당 별도 `bash -e` 프로세스다. 다섯 명령 모두 독립 `python3` 호출이고 `cd`·`export`·변수 전달·파이프가 전혀 없으므로 공유 쉘 상태 의존이 없다. `working-directory` 는 어디에도 없어 전부 `$GITHUB_WORKSPACE` 로 동일. 명령당 실패 동작만 "첫 실패에서 중단" → "각자 독립 판정" 으로 바뀌며, 이게 의도한 변경이다. 파이프가 없어 `pipefail` 유무 차이도 무해.
- **잡이 여전히 실패하는가**: 그렇다. `continue-on-error` 가 없고, 실패한 스텝은 이후 스텝의 성공과 무관하게 잡을 실패로 확정한다. 은폐가 아니라 노출 방향이 맞다.
- **캐시/아티팩트 영향**: 없음. 이 잡에는 `actions/cache`, `upload-artifact`, `setup-*` 캐시 스텝이 전혀 없고 `actions/checkout@v4` 하나뿐이다. 스텝 분해로 인한 오버헤드는 스텝당 프로세스 기동 수준이라 5분 상한에 무의미하다.

## 4. 그 외 관찰 (비차단)

- **[LOW]** `!cancelled()` 는 `checkout` 실패에도 적용된다. 체크아웃이 깨지면 예전에는 전부 skip 됐지만 이제 9개 스텝이 빈 워크스페이스에서 전부 실패해 로그가 시끄러워진다. 은폐는 아니므로 감수 가능. 신경 쓰이면 조건을 `!cancelled()` 대신 `success() || failure()` 로 바꿔도 동일하고, 근본적으로는 체크아웃 실패가 드물어 그대로 두는 편이 게으르고 옳다.
- **[LOW]** 조건이 9개 스텝에 복붙된다. GitHub Actions 에 잡 레벨 스텝 기본 `if` 가 없어 다른 방법이 없다. 스텝 하나를 새로 추가할 때 `if:` 를 빠뜨리면 그 스텝부터 다시 은폐가 시작되는 구조적 함정이 남는다 — 추가된 주석이 이 함정을 명시하고 있으니 현 시점 완화로 충분하다.
- **넘겨받은 CI 증거(잡 97224951335, 34초, 9개 downstream 성공)** 는 이 리뷰에서 재현하지 않았다. 위 §3 판정은 그 서술이 아니라 YAML 과 Actions 조건 의미론만으로 도출했고, 그 증거는 결론과 일치하는 방향의 보강일 뿐 근거로 계산하지 않았다.

## 5. 좋았던 점

- `continue-on-error` 를 쓰지 않은 판단이 정확하다. 그것이야말로 "실패를 감추는" 흔한 오답이다.
- 번들을 나누면서 각 스텝에 서술적 이름을 붙여, Actions UI 에서 어느 계약이 깨졌는지 로그를 펼치지 않고 바로 읽힌다.
- 주석이 날짜·PR 번호·`always()` 를 안 쓴 이유까지 남겨, 6개월 뒤 이 조건을 지우려는 사람을 막는다.
- 스텝 재정렬·타임아웃 변경 같은 무관한 변경을 섞지 않았다. 진단 가능한 최소 diff.
- 리베이스 충돌 해소가 정확했다 — 분할-스텝/`!cancelled()` 구조를 유지하면서 `#517`이 추가한 유닛테스트 스텝을 병합된 main과 같은 상대 위치에 복원했고, `git diff origin/main` 은 이제 순수 리팩터로만 남는다.

Verdict: cleared
