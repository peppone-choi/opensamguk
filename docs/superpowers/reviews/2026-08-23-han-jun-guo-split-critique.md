# Review: 후한 1급 행정단위 郡/國/尹 분리 (PR #507)

Scope: `tools/map/` (`junguozhi_contract.py`, `build_han_places.py`, `build_external_places.py`, `build_terrain_grid.py`, `tests/test_junguozhi_contract.py`), `tools/scenario/validate_han_route_node_selection.py`, `data/curated/han/` (`administrative-units.json` 및 route-node candidate/review-policy/selection/migration 앵커) — 1급 단위의 `groupType`/`kind` 어휘 분리와 그에 따른 PINNED 앵커 갱신
Verdict: cleared

비평자는 이 변경의 작성에 관여하지 않은 별도 에이전트다. 작성자가 자기 작업을 승인하지 않았다. 작성자 보고를 전제로 삼지 않고 앵커·사료·코드를 각각 직접 재현해 대조했다. 확인하지 못한 항목은 UNKNOWN으로 남겼다.

## 요약

앵커 갱신은 **작성자 주장대로 안전하다** — 4개 아티팩트 모두 sha256 문자열 외 변경 라인이 0이고, 780개 노드 결정은 보존된다. 재생성 결정성도 3회 재현된다. 그러나 **분류 자체에 blocker 2건**이 있다. 하나는 같은 커밋 안에서 尹 4개가 두 개의 서로 다른 `kind` 값으로 갈라지는 자기모순이고, 다른 하나는 `METROPOLITAN` 신설의 근거로 인용된 사료가 그 결론을 뒷받침하지 않을 뿐 아니라 같은 百官志의 다른 권이 정면으로 반박한다는 점이다.

## 앵커 검증 (최우선 항목) — 통과

**주장: "provenance 해시 문자열 외 내용 변경 없음."** 개수 세기가 아니라 내용 대조로 확인했다. 4개 아티팩트 각각에서 sha256을 포함하지 않는 변경 라인 수를 셌다:

```
git diff main...HEAD -- <file> | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | grep -viE 'sha256' | wc -l
  route-node-migration-v1.json            → 0
  route-node-review-policy-v1.json        → 0
  route-node-selection-candidates-v1.json → 0
  route-node-selection-v1.json            → 0
```

변경 라인 전량이 해시 문자열이다. 780개 노드의 선택/거부/사유가 담긴 라인은 한 줄도 diff에 나타나지 않는다 — 개수가 아니라 diff 자체가 비어 있으므로 내용 보존이 성립한다. 검증기 실물 실행도 같은 값을 낸다:

```
$ python3 tools/scenario/validate_han_route_node_selection.py
han route-node selection approved production manifest ... approved=780 scenarios=15
```

**결정성(항목 8) 재현.** `audit_junguozhi_source.py --out` 3회 실행 결과가 서로 같고, 커밋된 파일 및 `PINNED_ADMINISTRATIVE_CATALOG_SHA256`(`validate_han_route_node_selection.py:101`)과도 같다:

```
8d1ab71c805a00f8cdb0e2502126a872ee070ff148410de989be5127cbbdc134   ×3 (재생성)
8d1ab71c805a00f8cdb0e2502126a872ee070ff148410de989be5127cbbdc134   data/curated/han/administrative-units.json
8d1ab71c805a00f8cdb0e2502126a872ee070ff148410de989be5127cbbdc134   PINNED_ADMINISTRATIVE_CATALOG_SHA256
```

`tools/map/tests/test_junguozhi_contract.py` 10건 전부 통과, `tools/agent-system/check.py`는 findings 0건이다.

## [설계] 앵커가 무관한 변경에도 깨진다 (항목 3)

앵커는 **불가피하게** 깨진 게 맞다. 다만 그 불가피함이 설계의 정당성은 아니다.

연쇄는 기계적이다. 앵커 대상이 카탈로그 **파일 전체의 sha256**이라, `groupType` 같은 결정과 무관한 가산 필드 하나가 카탈로그 해시를 바꾸고 → candidate manifest가 그 해시를 본문에 박고 있으므로 candidate 자신의 해시가 바뀌고 → policy·selection·migration이 줄줄이 따라 바뀐다. 이번 diff가 정확히 그 모양이다(위 0-라인 대조).

문제는 이것이 **매번 반복된다**는 점이다. 앵커는 "사람이 리뷰한 780개 결정이 바뀌었는가"와 "무관한 필드가 추가되었는가"를 구별하지 못한다. 그래서 앞으로 카탈로그에 필드를 더할 때마다 "앵커를 갱신했다"는 의례가 반복되고, 그 의례는 **실제 결정 변경을 숨긴 갱신과 바이트 수준에서 구별되지 않는다**. 고정 장치가 리뷰 게이트에서 단순 변경 탐지기로 격하된다. 이번 PR은 그 첫 사례다.

완화 장치가 아주 없지는 않다 — 검증기가 재실행되어 `approved=780`을 다시 보고한다. 다만 그 값이 앵커와 독립적인 내용 재계산인지 앵커에 의존하는 값인지까지는 이번 리뷰에서 코드를 전수 추적하지 않았다(**UNKNOWN**).

권고: 카탈로그 파일 해시와 별개로 **결정 투영(node id → 판정 → 사유)만의 해시**를 앵커로 둘 것. 그러면 가산적 스키마 변경에도 사람이 리뷰한 앵커가 살아남고, 앵커가 깨졌다는 사실이 다시 유의미한 신호가 된다.

## [blocker] 尹 4개가 같은 커밋 안에서 두 값으로 갈라진다 (항목 7)

이 PR의 선언된 목적은 "뭉개진 것을 분리"하는 것인데, 같은 커밋이 **새로운 뭉갬을 하나 만든다**. 같은 4개 단위(河南尹·京兆尹·左馮翊·右扶風)가 생산자에 따라 다른 `kind`로 나온다:

| 생산자 | 대상 | 결과 |
|---|---|---|
| `junguozhi_contract.py:243-245` (`group_type`) | 尹/翊/風 4개 전부 | `METROPOLITAN` |
| `build_external_places.py:83,85` | 左馮翊, 右扶風 | `METROPOLITAN` |
| `build_han_places.py:59` (`TIER`) | `TYPE_CH == '尹'` | **`COMMANDERY`** (이 PR이 안 건드림) |

책상 위 추론이 아니라 실물 CHGIS 원본으로 확인했다. 220년 pref 레이어에는 `TYPE_CH == '尹'` 행이 정확히 2건 있고, 둘 다 이 PR 이후에도 `COMMANDERY`로 떨어진다:

```
TYPE_CH counts (220년): {'郡':106, '国':9, '州':4, '尹':2, '典农校尉':1, '侯国':1}
'河南尹' TYPE_CH='尹' -> TIER ('COMMANDERY', 6)
'京兆尹' TYPE_CH='尹' -> TIER ('COMMANDERY', 6)
```

즉 지도 파이프라인에서 河南尹·京兆尹은 `COMMANDERY`, 左馮翊·右扶風은 `METROPOLITAN`이 된다. 카탈로그는 넷 다 `METROPOLITAN`이라 말한다. **한 파이프라인 안에서 같은 부류가 두 값으로 쪼개졌고, 카탈로그와 지도가 두 단위에 대해 서로 다른 답을 낸다.**

`build_han_places.py:58`에 새로 붙은 주석이 郡/國 분리를 사료로 설명하면서 바로 다음 줄의 `'尹': ('COMMANDERY', 6)`은 손대지 않은 채 남겨 둔 것이, 이 누락이 의도가 아니라 실수임을 보여준다.

덧붙여 `tools/map/` 어디에도 `kind` 허용집합 검증이 없다(grep 결과 0건). `METROPOLITAN`은 검증되지 않는 자유 문자열이라, 오타가 조용히 통과한다.

## [blocker] METROPOLITAN 인용이 결론을 뒷받침하지 않는다 (항목 4·6)

먼저 분명히 해 둔다 — **인용은 조작되지 않았다.** 세 인용 모두 실재를 verbatim으로 확인했다. 문제는 세 번째 인용이 도출된 결론을 지지하지 않고, 같은 志의 다른 권이 그 결론을 반박한다는 점이다.

**확인된 인용 1 (문제 없음).** 卷113 「凡郡、國百五」 — 실재한다:

> [後漢書 卷113 郡國志 郡國五] …省縣漸復分置，至于孝順，《凡郡、國百五》，縣、邑、道、侯國千一百八十…

**확인된 인용 2 (결론은 맞으나 인용문이 잘렸다 — 아래 minor 참조).** 卷118 百官志:

> [後漢書 卷118 百官志 百官五 州郡] …其屬國都尉。《屬國，分郡離遠縣置之》，如郡差小，置本郡名。

**문제의 인용 3.** 작성자는 `junguozhi_contract.py:239-241`과 `build_external_places.py:81-82`에서 尹/翊/風을 「郡도 國도 아닌 수도권 특수 행정구역(三輔 등, 卷118 百官志 州郡)」이라고 단정한다. 卷118 百官志 州郡의 해당 대목은 이것이다:

> 凡州所監都爲京都，置尹一人，二千石，丞一人，毎郡置太守一人，二千石，丞一人。

이 문장이 가르는 것은 **장관의 관직**이다 — 京都에는 尹을, 郡에는 太守를 둔다. 둘 다 二千石으로 질(秩)도 같다. **단위가 郡이 아니라는 말은 어디에도 없다.** 관직 구분을 단위 종류 구분으로 옮겨 읽은 것이다.

그리고 같은 百官志 卷117이 그 단위 종류 주장을 정면으로 반박한다:

> 司隸所部郡七。河南尹一人，主京都，特奉朝請。其京兆尹、左馮翊、右扶風三人，漢初都長安，皆秩中二千石，謂之三輔。中興都雒陽，**更以河南郡爲尹**，以三輔陵廟所在，不改其號，但減其秩。**其餘弘農、河內、河東三郡。**

세 군데가 결정적이다.

1. 「司隸所部郡七」 — 司隸부의 일곱(河南尹·河內·河東·弘農·京兆尹·左馮翊·右扶風)을 사료가 **통틀어 郡이라 센다**. 문제의 4개가 그 안에 들어 있다.
2. 「更以河南郡爲尹」 — 河南**郡**을 尹으로 고친 것이다. 郡인데 장관 호칭이 尹이라는 뜻이지, 郡이 아니게 되었다는 뜻이 아니다.
3. 「其餘弘農、河內、河東三郡」 — "**나머지** 세 郡"이다. 앞의 넷도 같은 郡 집합의 원소로 세고 있어야 성립하는 문장이다.

**내부 산술 모순.** 이 PR은 81+20+4=105를 「凡郡、國百五」와 맞춰 검증 근거로 삼는다(`test_junguozhi_contract.py:76-80`). 그런데 그 4개가 「郡도 國도 아니」라면 애초에 「郡、國」 105 안에 들어갈 수 없다. **분류가 부정하는 집합의 총계로 그 분류를 검증하고 있다.** 105가 맞으려면 4개는 郡이거나 國이어야 하고, 4개가 郡도 國도 아니라면 총계는 101이어야 한다. 둘 다일 수는 없다.

카탈로그 실물도 사료가 이들을 같은 층위로 다룬다는 쪽을 지지한다 — 넷 다 卷109 司隸 안에서 弘農郡·河內郡·河東郡과 나란히 표제로 실린다(`sourceVolume: 109`).

요구: `METROPOLITAN` 신설을 철회하고 尹/翊/風을 `COMMANDERY`로 두거나(사료 표현 「司隸所部郡七」에 부합, blocker 1도 동시에 해소된다), 굳이 분리를 유지하려면 **관직 구분이 아니라 단위 구분임을 입증하는 인용을 새로 제시**하고 105 산술과의 모순을 해소할 것. 현재 인용으로는 후자가 서 있지 않다.

## [minor] 인용문이 뜻이 바뀌는 지점에서 잘렸다

`junguozhi_contract.py:238-239`는 屬國 근거를 「屬國，分郡離遠縣置之，如郡」으로 적는다. 실물은 「…如郡**差小**，置本郡名」이다. `差小`("郡보다 작다")를 떼면 "郡과 같다"로 읽힌다.

결론(屬國 → `COMMANDERY`) 자체는 유지된다 — 오히려 「如郡差小」와 屬國都尉가 太守를 대신한다는 서술이 郡급 취급을 지지한다. 다만 path:line과 verbatim 인용을 근거로 삼는 저장소에서 어구 중간을 끊어 뜻이 달라지는 인용은 그대로 둘 수 없다. 전문으로 고칠 것.

## 검증했고 문제 없는 항목

**항목 5 — 105 내역.** 카탈로그 실물 집계가 주장과 일치한다: `{'COMMANDERY': 81, 'KINGDOM': 20, 'METROPOLITAN': 4}`, 총 105 그룹. 屬國은 정확히 6개(廣漢·蜀郡·犍為·張掖·張掖居延·遼東屬國)이므로 순수 郡 75 + 屬國 6 = 81도 맞는다. KINGDOM 20개 목록도 卷109-113 표제와 대조 가능한 형태로 실려 있다. (산술과 카탈로그 내용은 일치한다는 뜻이며, 4개를 105에 넣는 것의 정합성은 위 blocker 참조.)

**항목 7 — SEAT_KINDS 누락 없음.** `build_terrain_grid.py`의 교체는 4곳 전부다(`:498`, `:525`, `:587`, `:607`). 같은 파일에 `kind == 'COMMANDERY'` 류 단일비교가 더 남아 있지 않음을 grep으로 확인했다. 이 파일에 한해서는 전수 교체가 맞다. (다른 파일의 누락은 blocker 1이 다룬다.)

**항목 9 — PR #506과 충돌하지 않는다.** 우려는 성립하지 않는다. #506의 등급 테이블은 **2급만** 등록한다:

```
web/game/components/game/HanMapCanvas.tsx:68-69
  TIER2_MARKER_ZOOM = { COUNTY: 2.2, MARQUISATE: 2.2 }
  TIER2_LABEL_ZOOM  = { COUNTY: 5.5, MARQUISATE: 5.5 }
```

1급 렌더링 경로는 `kind`를 보지 않는다. 마커는 `if (!c.seat && !ext && (markerZoom === undefined || ...)) continue;`(`:180`)라 `c.seat`면 등급표와 무관하게 그려지고, 1급 라벨은 `data.juns`를 돌며 `JUN_LABEL_ZOOM`만 본다(`:206-214`). `KINGDOM`·`METROPOLITAN` 치소는 `COMMANDERY`였을 때와 똑같이 그려진다. 등급표에 없는 값이 "안 그림"이 되는 것은 2급 비(非)치소 점에 한정되는데, 이 PR은 2급 `kind`를 건드리지 않는다.

추가로 이 PR은 `data/map/han-tiles.json`을 재생성하지 않는다(diff 없음). 현재 커밋된 타일의 kind 분포는 `{'COUNTY': 958, 'COMMANDERY': 146, 'EXTERNAL_PLACE': 37, 'PROVINCE': 3}`로, 새 값은 아직 데이터에 없다. 즉 blocker 1의 불일치도 **지도 빌드를 다시 돌리는 시점에 발현하는 잠복 결함**이지 지금 깨져 있는 상태는 아니다.

**항목 6 — 코드 계약 divergence는 잠복이지 실동작 파손은 아니다.** `AdministrativeLevel`(`logic/src/main/kotlin/opensamguk/logic/v2/geo/AdministrativeContracts.kt:57`)에 `METROPOLITAN`이 없는 것은 맞다. 다만 소비 측을 추적한 결과 **지금 터질 경로는 없다**: `groupType`을 읽는 곳은 스키마 허용집합(`validate_han_route_node_selection.py:184`)과 테스트뿐이고, `AdministrativeLevel`은 이 카탈로그에서 역직렬화되는 지점이 없다(`.kt` 전수 grep 결과 선언·데이터클래스 필드·테스트 3종뿐). "알 수 없는 값으로 떨어질 위험"은 오늘 시점에서는 없다. 다만 blocker 2가 해소되어 `METROPOLITAN`을 유지하기로 한다면 enum에 근거와 함께 추가해 어휘를 한 곳에 모을 것.

## 관찰 (결함 아님, 후속 판단 필요)

`'国' → KINGDOM` 전환이 CHGIS 220년 스냅샷에서 실제로 재분류하는 점은 9개뿐이다(`TYPE_CH` 집계의 `'国':9`). 카탈로그는 KINGDOM 20개다. 카탈로그는 順帝대 郡國志이고 CHGIS 스냅샷은 220년이라 그 사이 國→郡 전환이 있었으므로 **차이 자체는 예상된 것**이다. 다만 이후 카탈로그 `groupType`과 지도 `kind`를 대조하는 검사를 만들 계획이라면 이 11개 차이가 오탐으로 잡힐 것이므로, 두 어휘가 서로 다른 시점을 말한다는 점을 그때 명시해야 한다.

## 판정 근거

blocker 2건이 모두 이 PR의 **핵심 주장** — "사료에 근거해 1급 단위를 올바르게 분리했다" — 을 직접 겨눈다. 하나는 분리가 파이프라인 안에서 일관되지 않다는 것이고, 다른 하나는 신설된 등급의 사료 근거가 서지 않으며 PR 자신의 105 산술과 모순된다는 것이다. 앵커 갱신은 안전하고 결정성도 재현되지만, 그 위에 실린 분류가 서지 않으면 결정성은 틀린 값을 안정적으로 재생산할 뿐이다.

## Re-review 2026-08-24 (independent, commit 400e40b6)

비평자는 이 재검토를 작성한 별도 에이전트다. `400e40b6`의 커밋 메시지 주장을 전제로 삼지 않고, 위 4개 blocker/minor/요청 항목을 코드·diff·사료 corpus·테스트·검증기 실행 결과로 각각 직접 재현했다. 작업은 `/Users/apple/Desktop/개인프로젝트/opensamguk-meta/worktrees/opensamguk/han-jun-guo-split` (branch `work/opensamguk/han-jun-guo-split`)에서 수행했다.

**1. Blocker 1 (尹 4개 이원화) — 해소 확인.**

```
$ grep -rn "METROPOLITAN" tools/map tools/scenario   # exit code 1, zero hits
```

`git diff 4f4cfc4a..400e40b6`로 직접 diff를 읽었다. `build_external_places.py`의 左馮翊/右扶風이 `"METROPOLITAN"` → `"COMMANDERY"`로, `build_han_places.py`의 `TIER['尹']`은 원래부터 `COMMANDERY`였고 이번에 손대지 않았다(주석만 정합성 설명 추가), `junguozhi_contract.py:group_type()`의 尹/翊/風 분기도 `"METROPOLITAN"` → `"COMMANDERY"`로 바뀌었다. 3개 생산자 전부 동일한 값을 낸다 — 자기모순 해소됨.

**2. Blocker 2 (인용 근거) — 해소 확인, 사료 corpus로 직접 재검증.**

`tools/map/junguozhi_contract.py:238-249`의 `group_type()` docstring을 읽었다. 卷118 백관지 대신 卷117 백관지 「司隸所部郡七。河南尹一人，主京都…其京兆尹、左馮翊、右扶風三人…謂之三輔。中興都雒陽，更以河南郡爲尹…其餘弘農、河內、河東三郡」를 인용한다. 로컬 corpus로 독립 대조:

```
$ grep -n "司隶所部郡七" data/corpus/hhs-117.txt data/corpus/baiguan.txt
data/corpus/baiguan.txt:384:...司隶所部郡七。
data/corpus/hhs-117.txt:98:...司隶所部郡七。
$ sed -n '100p' data/corpus/hhs-117.txt
　　河南尹一人，主京都，特奉朝请。其京兆尹、左冯翊、右扶风三人，汉初都长安，
皆秩中二千石，谓之三辅。中兴都雒阳，更以河南郡为尹，以三辅陵庙所在，不改其号，
但减其秩。其餘弘农、河内、河东三郡。
```

(고서 corpus는 간체로 저장되어 있으나 자구는 번체 인용과 정확히 일치한다.) 「司隸所部郡七」 바로 다음 문장이 이 4개 단위를 나열하고 「其餘弘農、河內、河東三郡」으로 마무리하므로, 인용은 verbatim이고 완전하다. `test_junguozhi_contract.py:76-85`의 `test_group_type_splits_commandery_from_kingdom`도 확인:

```python
self.assertEqual({"COMMANDERY": 85, "KINGDOM": 20}, dict(counts))
```

`METROPOLITAN` 키가 사라졌고 85+20=105로 산술이 자기완결적이다(105 전체가 COMMANDERY/KINGDOM 두 값 안에만 있음, 더 이상 "센 집합에서 제외된 4개"가 없음). 테스트 실행 결과는 아래 5번 참조.

**3. Minor (屬國 인용 절단) — 해소 확인, corpus로 직접 재검증.**

`junguozhi_contract.py:237-238`은 이제 「屬國，分郡離遠縣置之，如郡差小，置本郡名」 전문을 인용한다. corpus 대조:

```
$ grep -n "分郡離遠縣置之" data/corpus/hhs-118.txt
data/corpus/hhs-118.txt:18:...其屬國都尉。屬國，分郡離遠縣置之，如郡差小，置本郡名。世祖幷省郡縣...
```

`如郡差小，置本郡名` 포함, 절단 없음 — verbatim 일치.

**4. kind 허용집합 검증 — 추가 확인, 실물 동작 검증.**

`tools/map/build_external_places.py:264-269`와 `tools/map/build_han_places.py:65-70`에 각각 `ALLOWED_KIND` 상수 + load-time `raise ValueError` 어서션이 새로 생겼다. 실물 검증(트래킹 파일은 건드리지 않고, 파일 내용을 문자열로 읽어 in-memory 변조본만 `exec`):

```python
src = open('tools/map/build_han_places.py', encoding='utf-8').read()
bad_src = src.replace("'邑': ('COUNTY', 5),", "'邑': ('BOGUS_KIND', 5),")
exec(compile(bad_src, 'bad_copy', 'exec'), {})
# -> ValueError: unrecognized TIER kind(s): ['BOGUS_KIND']
```

실제 파일을 정상 상태로 import하면 오류 없이 로드되고(`ALLOWED_KIND = {'KINGDOM', 'PROVINCE', 'COUNTY', 'COMMANDERY'}`), 타입값 하나를 허용집합 밖으로 바꾸면 즉시 `ValueError`가 뜬다 — 어서션이 실제로 작동함을 확인했다. 트래킹된 파일은 변경하지 않았다.

**5. 결정성 & 다운스트림 체인 — 재현 확인.**

```
$ for i in 1 2 3; do python3 tools/map/audit_junguozhi_source.py --out /tmp/reverify-au-$i.json; done
PASS groups=105/105 units=1180/1180 types={'COUNTY': 1043, 'DAO': 19, 'MARQUISATE': 108, 'TOWN': 10}  (×3, byte-identical)
$ sha256sum /tmp/reverify-au-{1,2,3}.json data/curated/han/administrative-units.json
2ba4bcc5...  (전부 동일 해시, PINNED_ADMINISTRATIVE_CATALOG_SHA256과도 일치)

$ python3 -m unittest tools.map.tests.test_junguozhi_contract -v
Ran 10 tests ... OK   (10/10)

$ python3 -m unittest discover -s tools/scenario/tests -p "test_han_route_node*.py"
Ran 186 tests ... OK   (186/186)

$ python3 tools/scenario/validate_han_route_node_selection.py
han route-node selection approved production manifest ...: approved=780 scenarios=15
```

4개 다운스트림 아티팩트를 `git diff 4f4cfc4a..400e40b6`로 sha256 문자열을 제외하고 대조:

```
route-node-migration-v1.json            → 0 non-sha256 changed lines
route-node-review-policy-v1.json        → 0
route-node-selection-candidates-v1.json → 0
route-node-selection-v1.json            → 0
```

780개 노드 결정은 보존됨 — 커밋 메시지 주장대로 확인.

**6. 새로 도입된 문제 — 없음.** `400e40b6`의 diff를 라인 단위로 읽었다(`git diff 4f4cfc4a..400e40b6 -- tools/map/*.py tools/scenario/validate_han_route_node_selection.py tools/map/tests/test_junguozhi_contract.py`). `build_terrain_grid.py`의 `SEAT_KINDS`는 `{'COMMANDERY', 'KINGDOM', 'METROPOLITAN'}` → `{'COMMANDERY', 'KINGDOM'}`로 정확히 축소됐고, 앞서 (이전 리뷰 "항목 7")에서 확인된 4-site 비교 교체는 그대로 유지된다(이번 diff가 `build_terrain_grid.py`를 이 한 군데 외에는 건드리지 않음). `PINNED_*_SHA256` 3개 값이 재계산된 해시와 정확히 맞물려 갱신되었다.

**7. `tools/agent-system/check.py --strict --base origin/main` 실행 결과.**

```
- Errors: 1
- Warnings: 0
## Findings
- ERROR cross-agent-critique: Unresolved Verdict: fix-required blocks completion:
  docs/superpowers/reviews/2026-08-23-han-jun-guo-split-critique.md
```

다른 finding은 없다 — 유일한 실패 원인이 이 문서의 stale `Verdict: fix-required` 라인이라는 주장이 확인된다.

**결론.** 이전 비평이 제기한 blocker 2건과 minor 1건 모두 코드·사료 corpus·테스트로 독립 재확인되었고, 새로 도입된 문제는 발견되지 않았다. 위 `Verdict:`을 `cleared`로 변경한다.
