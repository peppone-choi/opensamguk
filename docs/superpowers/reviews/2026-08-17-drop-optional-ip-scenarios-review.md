# 옵션 IP 초상 세트 전량 제거(메인 레포 쪽) — 독립 적대적 리뷰

Scope: `data/extracted/scenario/` 16개 시나리오 JSON 삭제 + `_meta.json` 항목 제거, `app/gateway-api/src/main/resources/profile-icons/shared-manifest.json` 핀 SHA 갱신, 감사 문서·LEDGER 각주.
Verdict: fix-required

리뷰 대상: 브랜치 `chore-drop-optional-ip-scenarios`, 커밋 `c7827e3e`, base `origin/main`.
이 리뷰는 변경을 만든 레인과 **독립된 에이전트**가 작성했고, 앞선 자체 리뷰 본문은 전부 폐기하고 처음부터 재검증했다.
아래 모든 판정은 직접 실행한 명령의 출력에 근거한다.

---

## 판정 요약

| # | 축 | 결과 |
|---|---|---|
| 1 | 전수 스캔 완전성 (누락/과잉) | **PASS** — 정확히 16개, 과잉·누락 0 |
| 2 | 고아 참조 (코드/설정/테스트/docs/CI) | **FAIL→CLOSED (F1)** — 테스트가 깨졌고, 리뷰 중 `df8068e7`로 수정·재검증 green |
| 3 | `_meta.json` 정합 | **PASS** — count 81→65, 배열 길이·순서·포맷 무결 |
| 4 | 핀 SHA 실측 | **PASS** — URL·해시·치수 4/4 일치 (연동 결함은 F1에서 종결) |
| 5 | LEDGER 각주 처리 | **FAIL (F2)** — 각주의 핵심 주장이 사실과 다르다 |
| 6 | `web/game` vitest | **조건부 PASS** — 2 fail은 이 브랜치와 무관함을 증명 |
| 7 | 범위 밖 변경 | **PASS** — 스코프 밖 파일 0 |
| 8 | (추가 발견) 퍼지 잔여물 | **FAIL (F3)** — 포켓몬 IP 맵 데이터가 메인 레포에 남았다 |

---

## 1. 전수 스캔 완전성 — PASS

레인의 주장을 믿지 않고 `origin/main` 트리 전체를 다시 파싱했다.

```
$ python3 … git ls-tree origin/main data/extracted/scenario/ → 각 파일 json.load → iconPath 집계
82 files on origin/main
'.'                    65 [0,1,1010…1120,2,2010…2190,2220,2221,2400,2401,2500,2501,2700…2704,900…914]
'강서유서월드'          2 ['2800','2801']
'걸그룹'                2 ['2140','2141']
'롤시나리오'            4 ['2900','2901','2903','2904']
'루드라사움'            1 ['2171']
'삼모시네마틱유니버스'  2 ['2600','2601']
'스타1프로게이머'       1 ['2200']
'쿠키런킹덤'            1 ['2300']
'포켓몬스터'            1 ['2210']
'환상향'                2 ['2130','2131']
```

`iconPath`만으로 16 + 65 = 81이고, 삭제 집합은 정확히 그 16개다. **과잉 삭제 0, 누락 0.**

`iconPath` 필드만 본 판단이 아님을 증명하기 위해, 생존 65개 시나리오의 **모든 문자열 노드를 재귀 순회**해 이미지 확장자(`png|jpg|jpeg|webp|gif`)나 `/`를 포함하는 값을 전부 뽑았다. 결과: 이미지 확장자 문자열 **0건**, `/` 포함 문자열은 전부 로그 마크업(`</>`, `<L><b>…`)이었다. 장수 tuple에는 별도의 아이콘 참조 필드가 없고, 아이콘 참조 경로는 시나리오 최상위 `iconPath` 하나뿐이다(top-level key 집계 결과 21개 키 전부 81/81 존재, 아이콘 관련 키는 `iconPath`가 유일).

세트명 전문 grep으로 교차 확인:

```
$ for n in 걸그룹 … 삼국지6; do git grep -l -F "$n" origin/main -- 'data/extracted/scenario/*'; done
걸그룹 → _meta.json, scenario_2140, scenario_2141
…
삼국지6 → (0건)
```

삼국지 역사 시나리오(0·1·2·9xx·10xx·11xx)와 살아남은 2xxx(2180·2190·2220·2400·2500·2700계열 등) 중 삭제 세트를 참조하는 것은 **0건**. `삼국지6` 0건이라는 주장도 재현됐다.

맵 참조 교차 확인(`mapName`):

```
None → 삭제 12건 / 생존 45건       che → 생존 2 [2191,900]
chess → 생존 1 [2121]              cr → 생존 1 [910]
ludo_rathowm → 삭제 [2171] / 생존 1 [2180]
miniche → 삭제 [2200,2300] / 생존 11
miniche_b → 생존 3                 miniche_clean → 생존 1 [901]
pokemon_v1 → 삭제 [2210] / 생존 0   ← F3
```

`ludo_rathowm` 맵은 2180이 계속 쓰므로 이미지 레포에 남긴 조율자 판단이 맞다(`https://api.github.com/repos/peppone-choi/opensamguk-images/contents/game/map` → `['che','chess','cr','ludo_rathowm']`). `pokemon_v1`은 생존 소비자 0 → F3 참조.

## 2. 고아 참조 — **FAIL (F1, blocker)**

16개 코드를 `scenario_<code>` 토큰으로 레포 전체(`.github/`·`scripts/`·`tools/`·`web/`·`infra/`·`app/` 포함, `.git`/`node_modules`/`build`/`legacy` 제외) grep한 결과, 실행 경로 참조는 **0건**이다. 히트는 두 곳뿐이고 둘 다 문서다:

- `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/a2-scenario-seed-sha256.txt:37-69` — 닫힌 루프의 정적 sha256 스냅샷.
- `docs/superpowers/research/2026-08-17-asset-license-audit.md:289` — 이 변경이 추가한 부록 D 본문.

(레인이 쓴 "숫자만 grep" 방식은 `2200`·`2600` 같은 값이 골든 JSON·맵 좌표·전투 픽스처에 수백 건 걸려 신호가 되지 않는다. `scenario_` 접두 토큰으로 좁혀야 한다.)

**그러나 시나리오가 아니라 4항(핀 SHA)에서 고아가 발생했다.**

```
app/gateway-api/src/test/kotlin/opensamguk/gateway/profile/SharedProfileIconCatalogTest.kt:46
    assertTrue(requireNotNull(existing.deliveryUrl).contains("@1b6624d886c1b326a2feeda449288b41231df5ef/"))
```

이 테스트는 같은 파일 37행에서 **프로덕션 매니페스트를 classpath로 로드**한다(`SharedProfileIconCatalog.fromClasspath("profile-icons/shared-manifest.json")`). 이 브랜치가 그 매니페스트의 `delivery_url`을 `@05842c61…`로 바꿨으므로 46행 단언은 반드시 실패한다. 즉 **브랜치는 그 자체로 `:app:gateway-api:test`를 깬다.**

추정이 아니라 실제로 돌려서 확인했다:

```
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test \
    --tests '*SharedProfileIconCatalogTest*' --rerun-tasks

SharedProfileIconCatalogTest > production manifest preserves the established trusted shared icon() FAILED
    org.opentest4j.AssertionFailedError at SharedProfileIconCatalogTest.kt:46
11 tests completed, 1 failed
> Task :app:gateway-api:test FAILED
BUILD FAILED in 1m 27s
```

XML로도 교차 확인(exit code가 아니라 XML 기준):

```
app/gateway-api/build/test-results/test/TEST-opensamguk.gateway.profile.SharedProfileIconCatalogTest.xml
<testsuite … tests="11" skipped="0" failures="1" errors="0" …>
FAILURE: org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
```

### F1 후속 — 리뷰 중 수정됨, 재검증 green

이 리뷰를 쓰는 도중 다른 레인이 `df8068e7 fix(test): OPENSAM 공유 아이콘 카탈로그 테스트의 리비전 핀을 새 SHA로 갱신한다`를 커밋해 46행 리비전을 `05842c61…`로 갱신했다(단언 대상은 PHP 골든이 아니라 **핀 계약**이므로 갱신이 정상 경로다 — 골든 약화 아님). 그 커밋 위에서 직접 재검증했다:

```
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --rerun-tasks
BUILD SUCCESSFUL in 2m 15s
13 actionable tasks: 13 executed

$ (app/gateway-api/build/test-results/test/*.xml 집계)
suites=33 tests=200 failures=0 errors=0 skipped=0
```

**F1 = CLOSED.** (단, `df8068e7`이 스테이징돼 있던 이 리뷰 파일 재작성분까지 함께 삼켰다 — 커밋 경계가 섞였을 뿐 내용 손실은 없다.)

## 3. `_meta.json` 정합 — PASS

```
old keys == new keys (9개, 순서 동일)
old count field 81  len(scenarios) 81
new count field 65  len(scenarios) 65        ← count == 실제 배열 길이
removed = scenario_2130,2131,2140,2141,2171,2200,2210,2300,2600,2601,2800,2801,2900,2901,2903,2904  (기대 16개와 정확히 일치)
added   = []
order preserved: True                        ← insertion order 규칙 위반 없음
files on branch: 65  meta codes: 65  identical: True
```

포맷 드리프트 없음: 1022→830줄(= 1022 − 16×12), 헤더 20줄 diff 0, 말미 개행 `0a` 유지. JSON 파싱 유효.

## 4. 핀 SHA — 값은 PASS

두 URL을 jsDelivr·raw 양쪽에서 실제로 내려받아 바이트를 해시했다.

```
cdn.jsdelivr.net/…@05842c61…/icons/1001.jpg    → HTTP 200  sha256=4d27da9a…d3b5  dims=(64,64)
raw.githubusercontent.com/…/05842c61…/icons/1001.jpg → HTTP 200  sha256=4d27da9a…d3b5  dims=(64,64)
cdn.jsdelivr.net/…@05842c61…/icons/default.jpg → HTTP 200  sha256=f53c76d0…141b  dims=(64,64)
raw.githubusercontent.com/…/05842c61…/icons/default.jpg → HTTP 200  sha256=f53c76d0…141b  dims=(64,64)
```

매니페스트의 `sha256`(`4d27da9a…d3b5` / `f53c76d0…141b`)·`width`/`height`(64/64)·`media_type`(image/jpeg)와 **4/4 일치**한다. 히스토리 재작성이 파일 바이트를 바꾸지 않았으므로 그 필드를 그대로 둔 판단은 옳다 — 실측으로 확인됨. 태그도 확인: `refs/tags/v2026.05.21 → 05842c61132fd5a71268fd9babd80ba74e27be62`.

## 5. LEDGER 각주 — **FAIL (F2)**

각주를 다는 방향 자체(과거 관측 사실은 보존하고, 라이브 핀은 `shared-manifest.json`에서만 갱신)는 옳다. `LEDGER.md`는 해시로 고정된 산출물이 아니라 각주 추가가 안전하고, 값을 소급 수정하면 "그 시점에 무엇을 관측했는가"라는 증거의 의미가 사라진다.

**문제는 각주의 사실관계다.** `docs/loops/opensam-91-profile-icon/LEDGER.md:85`는 이렇게 쓴다: *"that revision no longer exists in the rewritten history"*. 직접 확인한 결과 이는 **사실이 아니다.**

```
$ curl -o /dev/null -w "%{http_code}" https://raw.githubusercontent.com/peppone-choi/opensamguk-images/1b6624d886c1b326a2feeda449288b41231df5ef/icons/1001.jpg
200

$ curl -o /dev/null -w "%{http_code}" https://raw.githubusercontent.com/peppone-choi/opensamguk-images/1b6624d886c1b326a2feeda449288b41231df5ef/icons/%ED%8F%AC%EC%BC%93%EB%AA%AC%EC%8A%A4%ED%84%B0/%EA%B0%95%EC%B2%A0%ED%86%A4.png
200

$ curl https://api.github.com/repos/peppone-choi/opensamguk-images/contents/icons/%ED%8F%AC%EC%BC%93%EB%AA%AC%EC%8A%A4%ED%84%B0?ref=1b6624d8…
[{"name":"가디.png","path":"icons/포켓몬스터/가디.png","sha":"4330247316ec…","size":18540, …
```

즉 옛 커밋은 **참조되지 않을 뿐(unreferenced) 여전히 공개적으로 서빙되며, 제거했다는 2,335장이 그 SHA로 지금도 전부 내려받힌다.** 그리고 그 SHA는 이 레포에 두 군데(`LEDGER.md:85`, `SharedProfileIconCatalogTest.kt:46`)에 문자열로 박혀 있다 — 삭제 근거였던 IP·초상권 리스크가 실제로는 닫히지 않았다.

반면 신규 SHA에서는 정상적으로 사라졌다(`icons/걸그룹`·`icons/포켓몬스터`·`icons/롤시나리오` → API 404 3/3).

고쳐야 할 것:
1. `LEDGER.md:85` 각주 문구를 실측대로 정정한다 — "히스토리에서 참조가 끊겼으나 GitHub GC/Support purge 전까지 해당 SHA로 여전히 접근 가능하다."
2. 실제 접근 차단은 GitHub Support의 unreachable-object purge(또는 레포 재생성)가 유일한 경로다. 이 조치를 하기 전까지 "제거 완료"로 기록하면 안 된다 — 감사 문서 부록 D도 같은 단서를 달아야 한다.

## 6. `web/game` vitest — 조건부 PASS

```
$ cd web/game && /usr/local/bin/pnpm install --frozen-lockfile && /usr/local/bin/pnpm exec vitest run
 Test Files  2 failed | 63 passed (65)
      Tests  2 failed | 370 passed (372)
```

실패 2건:
- `__tests__/v2-space-proof.test.ts:109` "synthetic 2,000거점 전부 왕복 오류 0" — `Test timed out in 5000ms`. **`--testTimeout=60000`으로 재실행 시 통과**(1 failed | 1 passed / 44 passed) → 기본 5초 타임아웃 플레이크.
- `__tests__/live-noop-closures.test.tsx:326` (`pollCommandResult` waitFor) — 타임아웃 연장 후에도 실패.

이 브랜치가 원인이 아님의 증거: `git diff origin/main...HEAD --name-only | grep -c '^web/'` = **0** (웹 소스 0건 변경). 두 테스트 모두 `data/extracted/**`를 로드하지 않는다(`grep -rn "data/extracted\|scenario_2" web/game/__tests__/` → 0건). 따라서 시나리오 삭제와 인과관계가 없다. 다만 `live-noop-closures` 실패의 **선행 존재 여부를 `origin/main` 체크아웃에서 직접 재현하지는 않았다** — UNKNOWN으로 남기며, 이 브랜치의 책임은 아니다.

## 7. 범위 밖 변경 — PASS

```
$ git diff origin/main...HEAD --stat
 21 files changed, 64 insertions(+), 221700 deletions(-)
```
시나리오 16 + `_meta.json` + `shared-manifest.json` + `LEDGER.md` + 감사문서 + 이 리뷰 파일. 스코프 밖 파일 **0건**. `git status --short --branch` → 워킹트리 클린.

## 8. 추가 발견 — 퍼지 잔여물 **FAIL (F3)**

이번 삭제로 `pokemon_v1` 맵의 소비자가 0이 됐는데, 메인 레포에는 포켓몬 IP 데이터가 그대로 남았다.

- `data/extracted/map/pokemon_v1.json` (23 cities) · `infra/src/main/resources/map/pokemon_v1.json` — 도시명이 `태초마을`·`상록시티`·`회색시티`·`무지개시티`·`연분홍시티`·`석영고원`·`챔피언로드`·`사이클링로드`·`관동15로` … 즉 The Pokémon Company의 관동지방 지명 전량이다. 감사 문서 §2-2의 `포켓몬스터` 행과 **동일한 IP 등급**인데 그 행만 "제거됨"으로 갱신되고 이 맵 데이터는 남았다.
- `web/game/components/game/MapViewer.tsx:106` · `web/gateway/components/MapPreview.tsx:86` — `CDN_MAPS`에 `'pokemon_v1'`이 여전히 등재돼 있다. 그런데 이미지 레포에서 `game/map/pokemon_v1/`은 이미 삭제됐다(API 목록 `['che','chess','cr','ludo_rathowm']`, `…/game/map/pokemon_v1/pokemon_v1_road.png` → **404**, 대조군 `che_road.png` → 200). 타일 URL은 `MAP_CDN = ${IMAGE_CDN_BASE}/game/map`(`web/game/lib/constants.ts:10`) + `MapViewer.tsx:486-487`로 조립된다.

라이브 영향은 **없다** — 생존 시나리오 중 `mapName == pokemon_v1`이 0이므로 `cdnMapCode()`가 그 분기를 타지 않는다(§1 맵 집계). 그래서 blocker는 아니지만, IP 퍼지의 목적 기준으로는 **미완**이다.

고쳐야 할 것: 맵 JSON 2개와 `CDN_MAPS` 항목 2개를 같이 제거하거나, 제거하지 않는다면 감사 문서 부록 D에 "포켓몬 맵 데이터는 의도적으로 잔존, 사유 X"를 명시적으로 기록한다. 지금은 판단 자체가 문서에 없다.

## 9. 부수 지적 (non-blocking)

- `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/a2-scenario-seed-sha256.txt`를 손대지 않은 판단은 옳다 — 그 파일의 sha256이 `baseline/MANIFEST.md:34`에 고정돼 있어 편집하면 MANIFEST가 깨진다. 다만 `MANIFEST.md:104`가 "tracked 시드 소스 **82개**"라고 단언하는데 트리에는 66개(65+`_meta`)뿐이다. LEDGER에는 각주를 달고 여기에는 달지 않은 것은 처리 불일치다. 각주는 (a2 본문이 아니라) `MANIFEST.md` 또는 `.ai/current-state.md`에 달아야 해시가 유지된다.
- 감사 문서 부록 D의 "**깨지는 참조 없음**"은 시나리오 코드 기준으로는 참이지만, F1(테스트)·F3(역방향 고아)을 포함하지 않는다. F1 수정 후 문구를 정정해야 한다.

---

## 결론

전수 스캔·`_meta.json` 정합·핀 SHA 값·스코프 격리는 재현 검증으로 모두 **통과**했다. F1(`:app:gateway-api:test` 파손)은 리뷰 중 `df8068e7`로 수정됐고 33 suites / 200 tests / failures·errors 0으로 재검증해 **종결**했다.

남은 차단 사유는 두 건이다. (F2) LEDGER 각주의 핵심 사실 주장이 실측과 다르며, 삭제했다는 2,335장이 옛 SHA로 지금도 공개 접근 가능하다 — 이 작업의 존재 이유인 IP 리스크가 실제로 닫히지 않았다. (F3) 동일 IP 등급의 포켓몬 맵 데이터가 판단 기록 없이 메인 레포에 잔존한다. **두 건이 닫히기 전까지 `fix-required`다.**
