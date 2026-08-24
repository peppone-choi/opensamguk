# `--check-gate` 분리 (GH #534) 독립 비평

Scope: `work/opensamguk/ci-han-gate-check-534` @ `48ed5d8a` (`dede1024` 위), base `origin/main` @ `ad751195` — `build_han_world.py` 의 `--check-gate` 신설과 `ci.yml` 배선에 대해, (A) HanGateIndex.kt 의 junguozhi.json 독립성, (B) `build()` 리팩터 무해성, (C) CI 무조건 실행 및 RED/GREEN, (D) 한계 서술 정확성, (E) 저장소 계보 재발 여부를 재현 검증했다.

Verdict: cleared

검증은 `48ed5d8a` 와 `origin/main` 각각에 **새 detached 워크트리**를 파서 했다. 저장소를 고치지 않았고, 모든 뮤테이션은 원복 후 `git status --short` 가 비었음을 확인했다.

---

## 0. 왜 새 워크트리가 곧 CI 조건인가 (예상 밖의 수확)

`.gitignore` 108행이 `data/map/*` 로 전부 죽이는데, **바로 다음 줄에 명시적 예외가 있다**:

```
data/map/*
# ADR-LITE-040: 게임이 서빙하는 타일맵만 예외. 원본·중간 산출물은 계속 미커밋.
!data/map/han-tiles.json
```

그래서 새 체크아웃에는 `han-tiles.json` **만** 있고 `junguozhi.json` 은 없다:

```
$ ls data/map/          →  han-tiles.json   (그것뿐)
$ ls data/map/junguozhi.json
ls: data/map/junguozhi.json: No such file or directory
```

즉 **새 워크트리 = CI 체크아웃과 동일한 입력 상태**다. 아래 C 의 RED/GREEN 은 전부 이 상태에서 돌렸고, 매 단계 부재를 `ls` 로 확인했다. 이 `!data/map/han-tiles.json` 예외가 `--check-gate` 를 성립시키는 구조적 근거이고, 우연이 아니라 ADR-LITE-040 으로 못 박힌 의도라는 점이 이번 설계의 가장 튼튼한 부분이다.

## A. 독립성 주장 — 재현됐다, 그리고 구현자가 시험한 것보다 강하게 성립한다

구현자는 households 훼손 하나만 시험했다. 지시대로 **구조를 흔드는** 뮤테이션 12종으로 확장해 매번 전체 재생성 후 세 산출물의 SHA-256 을 비교했다.

| junguozhi.json 뮤테이션 | 빌드 | **HanGateIndex.kt** | HanCityConst.kt | han.json |
|---|---|---|---|---|
| households 절반 (구현자 시험) | ok | **동일** | 변경 | 변경 |
| 마지막 20개 郡 **삭제** | ok | **동일** | 변경 | 변경 |
| 河南尹(수도) 항목 **삭제** | ok | **동일** | 변경 | 변경 |
| 가짜 郡+縣 **추가** | ok | **동일** | 변경 | 변경 |
| 郡 이름 10개 개명 | ok | **동일** | 변경 | 변경 |
| 10개 郡의 縣 이름 전부 개명 | ok | **동일** | 동일 | 동일 |
| 縣 소속 이동 (郡0 → 郡1) | ok | **동일** | 동일 | 동일 |
| 전 郡의 縣 목록 **전부 제거** | ok | **동일** | 변경 | 변경 |
| 전 seat 교체 | ok | **동일** | 동일 | 동일 |
| places 순서 역전 | ok | **동일** | 동일 | 동일 |
| households 전부 null | 크래시 | — | — | — |
| places=[] | 크래시 | — | — | — |

**성립한 10종 전부에서 HanGateIndex.kt 는 바이트 동일**, 그중 5종은 HanCityConst.kt/han.json 을 실제로 움직였다(뮤테이션이 헛돌지 않았다는 증거). 항목 삭제·추가·개명·소속 이동 — 지시가 요구한 "구조를 흔드는" 시험을 모두 통과한다. 마지막 두 줄의 크래시는 `level_thresholds` 가 빈 리스트에서 터지는 것으로, 게이트 독립성과 무관하고 `--check-gate` 경로는 애초에 `level_thresholds` 를 부르지 않는다.

**코드 근거도 확인했다.** `canon_ju()` (156행) 는 `CANON_SRC.read_text()` 로 **`tools/map/build_junguozhi.py` 소스 줄을 정규식으로 파싱**한다 — JSON 산출물이 아니라 파이썬 리터럴이 맞다. 구현자 주장대로다.

**`c["zhi"]` 동명이의도 맞다.** 407행 `if not (c.get("zhi") and c["kind"] == "COUNTY")` 의 `c` 는 `tiles["cities"]` 원소, 즉 `han-tiles.json` 필드다. JUNGUOZHI 에서 온 `zhi` **변수**는 `build()` 안에서만 정의되고(452행) 454·470·483행 — households/level 계산에서만 쓰인다. 두 이름은 서로 다른 스코프에 산다. 여기서 구현자가 틀렸다면 판정이 뒤집혔겠지만, 틀리지 않았다.

**게이트가 무기력하게 상수인 것은 아니다** (독립성 주장의 필수 반대편 시험). 진짜 입력 3종을 각각 훼손하고 재생성 없이 `--check-gate` 만 돌렸다:

| 훼손 대상 | `--check-gate` |
|---|---|
| `CANON_105` 주석 `# 冀州`→`# 兗州` | **드리프트 → exit 1** |
| `units.json` 게이트 키 `烏桓`→`烏桓X` | **드리프트 → exit 1** |
| `han-tiles.json` 의 한 城 `zhi=False` | **드리프트 → exit 1** |
| (원복) | 드리프트 없음 → exit 0 |

선언한 세 입력 전부에 살아 반응하고 junguozhi 에만 무반응이다. 이게 정확히 원하는 모양이다.

## B. 리팩터 무해성 — 바이트 동일, stdout 까지

`origin/main` 워크트리와 `48ed5d8a` 워크트리에 **동일한 junguozhi.json** 을 넣고 전체 빌드를 각각 돌려 비교했다:

```
han.json         IDENTICAL
HanCityConst.kt  IDENTICAL
HanGateIndex.kt  IDENTICAL
stdout           IDENTICAL   (summary 출력까지 한 글자도 안 다르다)
```

"결과값·순서 동일" 주장은 성립한다. 추출 과정에서 새어나간 순서 의존·부작용은 없다. 재생성 결과가 커밋된 산출물과도 일치해(`git status` 가 비었다) 기존 드리프트도 없다.

추출된 지역변수 `cols`·`seat_kind` 가 `build()` 에 죽은 채 남았는지도 봤는데, 둘 다 실제로 쓰인다(`cols`→x좌표 환산, `seat_kind`→EXTERNAL_PLACE 판정). 죽은 코드 없음.

구조적으로도 이 리팩터가 **복제보다 낫다**: `build()` 가 `build_gate(sk)` 에 위임하므로 두 경로가 갈라질 수 없다. 게이트 로직을 `--check-gate` 용으로 따로 베꼈다면 그게 다음 이슈가 됐을 것이다.

## C. CI 에서 진짜 RED 가 되는가 — 된다

`ci.yml` 배선:
- `on:` 은 `push: [main]` + `pull_request` 이고 **`types`·`paths`·`branches` 필터가 없다**.
- `agent-system` job 에 `if:` 도 `matrix` 도 없다.
- 새 스텝은 `if: '!cancelled()'` — 앞 스텝이 깨져도 돈다.

→ **모든 PR 에서 조건 없이 실행된다.** `dede1024` 의 `if [ -f ... ]` 존재-가드는 완전히 제거됐고, 이제 가드 없는 진짜 게이트다.

junguozhi.json **부재 상태**(매번 `ls` 로 확인)에서 손으로 재현:

```
GREEN  --check-gate                 → "드리프트 없음 (gate)."  exit 0
RED    司隸→豫州 한 글자            → "드리프트: ...HanGateIndex.kt"  exit 1
RED    setOf 한 줄 삭제             → "드리프트: ...HanGateIndex.kt"  exit 1
GREEN  원복                          → "드리프트 없음 (gate)."  exit 0

(대조군) 기존 --check 를 같은 상태에서: "data/map/junguozhi.json 가 없다." exit 1
        ← 이것이 #534 가 말한 vacuous-fail 이고, 실재한다
```

exit code 만이 아니라 출력 문자열도 함께 읽었다. 부수적으로 `--check-gate` 는 **0.28초**라 job 의 `timeout-minutes: 5` 예산에 사실상 무영향이다(#513 계보 회피). CI 가 함께 돌리는 `tools/scenario/tests` 252건·`tools/map/tests` 28건도 이 브랜치에서 통과한다(OK, skipped=1 / skipped=10).

## D. 새 게이트가 못 잡는 것 — 한계 서술은 정확하다, 다만 한 가지가 후퇴했다

ci.yml 주석의 사실 주장을 하나씩 대조했다:

| 주석의 주장 | 검증 |
|---|---|
| `--check` 는 junguozhi.json 이 필요하다 | 참 (위 대조군) |
| junguozhi.json 은 gitignored, 체크아웃에 없다 | 참 (`.gitignore` 108행) |
| bare `--check` 는 영구 RED | 참 (재현) |
| HanGateIndex.kt 는 junguozhi/CHE 에 의존하지 않는다 | 참 (A, 10종 뮤테이션) |
| `--check-gate` 는 TILES/CANON_105/UNITS 만 읽는다 | 참 (junguozhi·che 부재로 GREEN 성립) |
| 셋 다 tracked | 참 (`git cat-file -e` 로 전부 확인) |
| HanCityConst.kt/han.json 드리프트는 여전히 미게이트 | 참, 과장도 축소도 없다 |

한계 서술은 정직하다. `ADR-LITE-039` 인용도 실체가 있다 — 다만 독립된 ADR 문서 파일은 없고 `.gitignore` 주석과 research 문서 참조로만 존재한다. 인용의 **내용**(CHGIS 파생 좌표, 재배포 금지)은 `.gitignore` 105행과 일치하므로 사실 오류는 아니다.

**후퇴 한 가지 (blocking 아님, 기록용).** `dede1024` 는 junguozhi 가 없을 때 `::warning::` 를 띄워 "HanCityConst.kt/han.json 은 게이트되지 않는다" 를 **CI 로그 UI 에 매번 보이게** 했다. `48ed5d8a` 는 그 분기를 통째로 지우면서 그 경고도 함께 지웠다 — 이제 그 한계는 아무도 안 읽는 YAML 주석에만 산다. 게이트가 실질적으로 강해진 것(가짜 스킵 → 진짜 검사)이 훨씬 크므로 순이득이지만, "산출물은 커밋되는데 입력이 없어 조용히 열화"(#536) 계보와 정확히 맞닿는 지점이라 적어둔다. 남은 두 산출물용 경고 스텝을 별도로 두는 것이 후속으로 자연스럽다.

## E. 계보 재발 여부 — 미끄러지지 않았다

| 계보 | 이번 건 |
|---|---|
| #528 "만들었는데 도달 불가" | 해당 없음. `HanGateIndex` 는 `CityConstRegistry.kt:273` 의 `gateKeys()` 가 실제로 쓰고, `HanGateRegionsTest.kt` 가 물린다. 게이트하는 대상이 죽은 산출물이 아니다. |
| #521·#534 "게이트는 있는데 CI 가 안 부름" | **이번 건이 바로 그 해소다.** 가드 없이 모든 PR 에서 돈다. |
| #513 "돌긴 하는데 안 끝남" | 해당 없음. 0.28초. |
| #536 "입력이 없어 조용히 열화" | 게이트된 축(HanGateIndex.kt)은 해소. 나머지 두 축은 여전히 노출돼 있고 구현자도 그렇게 적었다 — 다만 D 의 `::warning::` 상실로 **가시성**이 한 단계 낮아졌다. |

새로 만들어진 것이 "가드가 있어서 사실상 안 도는 게이트"(#521 재판)가 아닌지가 가장 큰 의심이었는데, 존재-가드가 제거됐고 부재 상태에서 RED 가 나오는 것을 직접 확인했으므로 그 함정은 피했다.

## 결론

구현자의 주장 1~6 을 전부 내 손으로 재현했고, 반증하지 못했다. 독립성 주장은 households 하나가 아니라 삭제·추가·개명·소속이동을 포함한 10종에서 성립하며, 게이트는 자기 실제 입력 3종에는 정상적으로 반응한다. 리팩터는 stdout 까지 바이트 동일이다. CI 배선은 무조건 실행이고 부재 상태에서 RED/GREEN 이 재현된다. 한계 서술에 과장이 없다.

남는 것은 blocking 이 아닌 관찰 하나: `::warning::` 상실로 미게이트 두 산출물의 CI 가시성이 낮아졌다.

### 미확인 (UNKNOWN)
- 실제 GitHub Actions 러너에서의 실행은 확인하지 못했다. 로컬 재현은 CI 와 동일한 입력 상태(새 체크아웃)에서 했으나 러너 자체는 돌려보지 않았다.
- `junguozhi.json` 은 다른 워크트리의 사본을 썼다. `tools/map/build_junguozhi.py` 로 재생성한 것과 동일한지는 확인하지 않았다 — 다만 이 파일로 돌린 `--check` 가 GREEN 이므로 커밋된 산출물과는 정합한다.
