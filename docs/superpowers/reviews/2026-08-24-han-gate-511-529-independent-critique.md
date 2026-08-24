# han GATE_PLACES 게이트 커버리지 (#511 / #529) 독립 비평

Scope: 브랜치 work/opensamguk/han-gate-529 커밋 d8c2f9c6 (base origin/main ad751195) 의 GATE_PLACES 추가·HanGateIndex.kt 생성물·HanGateRegionsTest 3종을 지명 실존성·뮤테이션 감도·생성물 드리프트·커밋 경계·사료 근거 축으로 독립 실측

Verdict: cleared

검토자: critic-529 (독립). 구현자 수치를 재현하지 않고 데이터에서 직접 계산했다.
환경: `JAVA_HOME=temurin-21` (기본 `JAVA_HOME` 이 25.0.2 라 Gradle 이 구성 단계에서 죽는다 — 아래 U-1).

---

## 1. 요약

구현자 주장은 **전부 재현됐고, 과장이 없다**. 반증 시도(개별 지명 27종 제거 · 키 통째 제거 · GATE_PLACES 전체 비우기 = 총 40종 뮤테이션)에서 **죽은 가지(지도에 없는 지명)는 0개**였고, 새로 추가된 지명 중 커버리지에 기여하지 않는 것도 없었다(별칭 이체자 쌍 제외 — 아래 F-1). 생성물은 소스와 바이트 일치하고 커밋은 정확히 3파일이다.

Blocking 없음. 아래 F-1~F-3 은 정보성(LOW)이다.

---

## 2. 지명 실존성 — 게이트 리스트에 죽은 문자열이 없다 (공격 1)

`han-tiles.json` 의 `juns[].nameCh` + `cities[].nameCh`(seatOwner 격자로 소속 郡 해석)로 실제 해석 결과:

```
$ python3 scratchpad/probe3.py     # gate_index() 의 place_to_juns 를 그대로 재현
西羌     -> juns [166] ['西羌']
越巂郡    -> juns [60] ['越巂郡']
越嶲郡    -> juns [60] ['越巂郡']
宕渠郡    -> juns [128] ['宕渠郡']
巴郡     -> juns [55] ['巴郡']
巴西郡    -> juns [132] ['巴西郡']
國內城    -> juns [123] ['國內城']
卒本     -> juns [115] ['卒本']
玄菟郡    -> juns [96] ['玄菟郡']
哀牢     -> juns [121] ['哀牢']
武陵郡    -> juns [46] ['武陵郡']
鬱林郡    -> juns [101] ['鬱林郡']
日南郡    -> juns [105] ['日南郡']
牂牁郡    -> juns [59] ['牂牁郡']
牂柯郡    -> juns [59] ['牂牁郡']
永昌郡    -> juns [62] ['永昌郡']
--- all GATE_PLACES values unresolved in map ---
(없음)
```

10개 지명 전부 실존한다. `GATE_PLACES` **전체**를 훑어도 미해석 값은 0이며, 유일한 미매칭 게이트 키는 생성기가 의도적으로 표에 안 넣고 리포트하는 `投馬國` 하나다(`gateMissing: ['投馬國']`, 기존 동작).

**이체자 쌍은 죽은 문자열이 아니다.** `越嶲郡`/`牂柯郡` 는 지도에서 각각 `越巂郡`(60) / `牂牁郡`(59) 과 **같은 郡 인덱스로 해석된다** — 즉 지도 안에 두 표기가 다 존재하고 같은 郡을 가리킨다. 존재하지 않는 이름을 적어둔 게 아니다. 기존 코드(`南中`, `青羌`, `叟`)가 이미 쓰던 패턴과 동일하다.

## 3. 전수 주장 독립 재현 (공격 3)

구현자 숫자를 쓰지 않고 `units.json` + 새 게이트 인덱스로 직접 계산했다.

```
cityCount 780   gateMissing ['投馬國']
han units total: 134   with ReqRegions: 68
ZERO: []
```

- han 병종 **총 134종**, 그중 `ReqRegions` 를 가진 것이 **68종**. 68종 전부 nonzero. 나머지 66종은 지역 제약 자체가 없다.
- 타깃 5종: `2147 청강 4` · `2152 종민병 1` · `2164 고구려기병 1` · `2166 상림만병 1` · `2184 맥궁수 1` — **구현자 수치와 완전 일치**.
- #529 6종: `2196 무릉만 노수 10` · `2197 오계만 도병 10` · `2198 장가이병 5` · `2199 월수 수병 4` · `2200 애뢰 노수 2` · `2201 오호만병 5`.

## 4. "1 城" 은 이 PR 이 만든 협소함이 아니다 (공격 2)

커버리지 분포(도달 가능 城 수 → 병종 수):

```
[(1, 30), (2, 3), (3, 2), (4, 5), (5, 4), (6, 2), (7, 2), (8, 1),
 (10, 2), (11, 4), (14, 8), (15, 1), (30, 1), (50, 1), (58, 1), (87, 1)]
```

`ReqRegions` 병종 68종 중 **30종이 원래부터 정확히 1 城**이다 — 산월병·선비기병·오환기병·남흉노기병·읍루주사·왜인 목궁수 등 이 PR 이 손대지 않은 부족 병종 전부가 여기 속한다. **1 城 = 부족 게이트 병종의 기존 설계**이고, 2152/2164/2166/2184 가 1 인 것은 게이트가 좁아서가 아니라 교집합이 구조적으로 한 城뿐이기 때문이다:

- 2152 종민병 = `巴西` ∩ `賨` → 城 571 하나.
- 2164/2184 = `幽州` ∩ `高句麗` → 城 728(玄菟郡) 하나. 國內城(762)은 高句麗 태그는 있으나 東夷라 州 키가 없어 `幽州` 를 못 채운다.
- 2166 상림만병 = `日南` ∩ `蠻` → 日南郡의 城 745 하나.

즉 이 PR 은 "0 → 1" 을 만든 게 아니라 **원래 이 지도에서 그 병종이 가질 수 있는 최대치**를 채웠다. 더 넓히려면 지명 표가 아니라 지도의 郡 구성이나 units.json 의 요구 조합을 손대야 하고, 그건 이 PR 범위가 아니다.

## 5. 뮤테이션 — 잡히지 않는 것을 찾았다 (공격 4)

40종을 파이썬으로 전수 돌리고(생성기 `build()` 를 in-memory 로 재실행), 그중 3종은 **Gradle 실물 테스트로 재확인**했다.

### 5.1 잡히는 것 (전수 테스트가 RED)

키 통째 제거:

```
[drop key 蠻 entirely]   zero-> 2166 상림만병, 2196 무릉만 노수, 2197 오계만 도병, 2201 오호만병
[drop key 高句麗]        zero-> 2164 고구려기병, 2184 맥궁수, 2192 고구려 산기병
[drop key 賨]            zero-> 2105 판순만병, 2120 적갑군, 2121 연노사, 2148 종수, 2152 종민병
[drop key 羌]            zero-> 2108 강족기병, 2128 철거병, 2147 청강, 2171/2172/2173 강족 3종
[drop key 夷]            zero-> 2198 장가이병, 2200 애뢰 노수
[drop key 武陵]          zero-> 2196, 2197      [drop key 牂牁] zero-> 2198
[drop key 越巂]          zero-> 2199            [drop key 永昌] zero-> 2200
[drop key 鬱林]          zero-> 2201
[GATE_PLACES = {}]       zero-> 64종
```

main 값으로 되돌리는(= 이 PR 을 정확히 무효화하는) 뮤테이션도 전부 잡힌다:

```
[revert 羌 to ["西羌"]]                zero-> 2147 청강
[revert 賨 to ["宕渠郡","巴郡"]]        zero-> 2152 종민병
[revert 蠻 to ["哀牢"]]                zero-> 2166, 2196, 2197, 2201
[revert 高句麗 to ["國內城","卒本"]]    zero-> 2164, 2184
```

개별 지명 중 **부하가 걸린(load-bearing)** 것:

```
[賨: remove '巴西郡']    zero-> 2152 종민병
[蠻: remove '武陵郡']    zero-> 2196, 2197
[蠻: remove '鬱林郡']    zero-> 2201
[蠻: remove '日南郡']    zero-> 2166 상림만병
[高句麗: remove '玄菟郡'] zero-> 2164, 2184
[夷: remove '永昌郡']    zero-> 2200
[永昌: remove '永昌郡']  zero-> 2200      [武陵: remove '武陵郡'] zero-> 2196, 2197
[鬱林: remove '鬱林郡']  zero-> 2201
```

Gradle 실물 재확인 (`--rerun-tasks --no-build-cache`, JDK 21):

```
### MUT drop_日南郡   tests="8" skipped="0" failures="1" errors="0"
    모집 가능 城 이 0인 han 병종: [상림만병(2166): [[日南…
### MUT drop_玄菟郡   tests="8" skipped="0" failures="1" errors="0"
    모집 가능 城 이 0인 han 병종: [고구려기병(2164): [[幽州…
```

정확히 타깃만 RED. 구현자의 `玄菟郡` RED 주장을 캐시 없는 재컴파일로 독립 재현했다.

### 5.2 F-1 (LOW) — 살아남는 뮤테이션 9종. 단, `--check` 가 전부 잡는다

전수 테스트가 **`≥1` 만 고정**하므로, 커버리지를 줄이되 0으로 만들지 않는 제거는 GREEN 이다:

```
[羌: remove '越巂郡']     변화 없음      [羌: remove '越嶲郡']  변화 없음
[夷: remove '牂牁郡']     변화 없음      [夷: remove '牂柯郡']  변화 없음
[牂牁: remove '牂牁郡']   변화 없음      [牂牁: remove '牂柯郡'] 변화 없음
[越巂: remove '越巂郡']   변화 없음      [越巂: remove '越嶲郡'] 변화 없음
[蠻:  remove '哀牢']      변화 없음
[賨:  remove '宕渠郡']    2105/2120/2121/2148: 11 -> 10
[賨:  remove '巴郡']      2105/2120/2121/2148: 11 -> 2
[高句麗: remove '國內城']  2192 고구려 산기병: 3 -> 2
[高句麗: remove '卒本']    2192 고구려 산기병: 3 -> 2
```

이체자 쌍(`越巂/越嶲`, `牂牁/牂柯`)은 서로가 서로를 덮어서 개별 제거가 무해한 것이므로 결함이 아니다 — 둘 중 하나만 지워도 나머지가 같은 郡을 잡는다. 실제로 문제 삼을 만한 것은 **`蠻` 의 `哀牢` 가 이 PR 이후 완전히 무부하가 됐다**는 점이다(`蠻` 을 요구하는 4종 전부가 武陵/鬱林/日南 로 커버된다).

다만 이 9종은 **모두 `HanGateIndex.kt` 생성물을 바꾸므로 `--check` 드리프트가 잡는다.** 실증:

```
$ # 蠻 에서 哀牢 제거 후
$ python3 tools/scenario/build_han_world.py --check
드리프트: common/src/main/kotlin/opensamguk/common/constants/HanGateIndex.kt
check_exit=1
$ # 원복 후
드리프트 없음.   restored_exit=0
```

즉 테스트 + `--check` 를 합친 게이트에는 구멍이 없다. **테스트만** 놓고 보면 커버리지 감소는 안 잡힌다는 사실만 기록해 둔다(정확한 성 수를 핀하는 골든 테스트를 추가하면 닫히지만, 城 id 가 생성기 재배치마다 밀리는 이 저장소에선 유지비가 이득보다 클 수 있다 — 권고하지 않는다).

## 6. 생성물 정합성 (공격 5)

```
$ python3 tools/scenario/build_han_world.py --check
드리프트 없음.
$ git status --short
(빈 출력)
```

세 산출물(`infra/.../map/han.json`, `HanCityConst.kt`, `HanGateIndex.kt`) 전부 소스와 바이트 일치. `GATE_PLACES` 변경이 `han.json`/`HanCityConst.kt` 를 건드리지 않는다는 것도 커밋에 그 둘이 없는 사실과 일치한다(게이트 인덱스만 소비).

## 7. 커밋 경계 (공격 6)

```
$ git show --stat d8c2f9c6
 common/src/main/kotlin/opensamguk/common/constants/HanGateIndex.kt   | 58 +++++-------
 logic/src/test/kotlin/.../military/HanGateRegionsTest.kt             | 92 ++++++++++++++--
 tools/scenario/build_han_world.py                                    | 29 ++++++-
 3 files changed, 140 insertions(+), 39 deletions(-)
```

정확히 3파일. `data/chgis-source/`, `data/map/junguozhi.json`, `han-places.json`, `terrain-grid.json`, `readings.json` **모두 미포함**. `git status --short` 도 전 구간(뮤테이션 실행 후 원복 포함)에서 비어 있다. ADR-LITE-039/040 격리 규약 위반 없음.

## 8. 테스트가 공허하지 않다 (공격 7)

`모든 han 병종은 ReqRegions 태그를 전부 가진 城이 최소 1개 있다(#511 전수)` 는 **진짜 전수**다:

```kotlin
val zeroCoverage = UnitCatalog.all("han").values.mapNotNull { unit ->
    val groups = unit.reqConstraints.filterIsInstance<UnitConstraint.ReqRegions>().map { it.reqRegions }
    if (groups.isEmpty()) return@mapNotNull null
    val covered = han.all().keys.any { id ->
        val gate = HanGateIndex.keys(id)
        groups.all { g -> g.any { it in gate } }
    }
    ...
```

하드코딩 목록이 없고 카탈로그를 돈다 — 앞으로 추가될 han 병종도 자동으로 덮인다. 개수만 세지 않고 실패 시 `병종명(id): groups` 를 뱉는다(위 RED 출력이 증거).

`#529` 테스트는 `cityWithKeys(郡, 부족)` 로 **같은 城 하나만 보유**시키고 `canRecruit` 로 실제 `isValid` 를 통과시킨다 — "태그를 채웠다"가 아니라 "그 城 하나로 모집된다"를 건다. 공허하지 않다.

주의점 하나: 전수 테스트는 프로덕션보다 **더 엄격**하다. 프로덕션은 제약별 독립 `any{}` 라 여러 城이 나눠 만족해도 되고, 게이트 키 외에 `regionIdByName` 폴백 경로도 있는데 테스트는 단일 城 + 게이트 키만 본다. 방향이 엄격한 쪽이라 위양성(가짜 GREEN) 위험은 없다.

## 9. 함정 주석 검증 — 주석이 프로덕션과 맞다 (공격 8)

`HanGateRegionsTest` 클래스 문서의 주장:
> `RecruitUnitAvailability.isValid` 가 제약별로 `ownCities` 전체를 `any{}` 로 독립 검사

`logic/src/main/kotlin/opensamguk/logic/actions/military/RecruitAlgorithm.kt:31-77` 실물:

```kotlin
for (constraint in unit.reqConstraints) {
    val ok = when (constraint) {
        ...
        is UnitConstraint.ReqRegions ->
            ownCities.keys.any { id -> cityConst.gateKeys(id).any(constraint.reqRegions::contains) } ||
                constraint.reqRegions.any { name -> cityConst.regionIdByName(name)?.let { ownRegions.contains(it) } == true }
```

`ReqRegions` 는 제약마다 **독립적으로** `ownCities` 전체를 `any{}` 한다. 여러 城 보유 시 서로 다른 城이 각 제약을 나눠 만족해도 통과한다는 주석은 **정확하다**. `ForbidRegions` 가 주둔 城(`general.cityId`) 기준이라는 주석도 코드와 일치한다. 거짓 주석 아님.

## 10. 테스트명 정정이 정직하다 (공격 9)

`origin/main` 의 `data/unitset/units.json` 직접 덤프:

```
$ git show origin/main:data/unitset/units.json | python3 -c "..."
2196 무릉만 노수 [ReqTech 1000, ReqRegions['武陵'], ReqRegions['蠻']]
2197 오계만 도병 [ReqRegions['武陵'], ReqRegions['蠻']]
2198 장가이병   [ReqRegions['牂牁'], ReqRegions['夷']]
2199 월수 수병   [ReqRegions['越巂'], ReqRegions['叟']]
2200 애뢰 노수   [ReqRegions['永昌'], ReqRegions['夷']]
2201 오호만병   [ReqRegions['鬱林'], ReqRegions['蠻']]
```

2196~2201 **전부 이미 郡/부족이 별도 `ReqRegions` 항목으로 분리**돼 있다. "base 에 B1 버그가 없다"는 주석은 사실이고, `(B1 복원)` → `(B1 회귀 방어)` 정정은 정직하다.

## 11. 사료 근거 검증 (공격 2 — 근거 강도)

corpus 인덱스로 인용문을 한 자씩 대조했다. **인용 4건 전부 실재하고 원문과 일치한다.**

| 주석 인용 | 대조 결과 |
|---|---|
| 三國志 蜀書一 劉二牧傳「焉出青羌與戰」 | ✅ `[三國志 卷31 蜀書一 劉二牧傳] …使引兵還擊焉，《焉出青羌與戰》，故能破殺` |
| 華陽國志 卷九 李特志「賨人敬信…自巴西之宕渠」 | ✅ `[華陽國志 卷九 李特志] …以鬼道教百姓，《賨人敬信》；值天下大亂，自巴西之宕渠` |
| 漢書 卷028 地理志「玄菟郡…縣三：高句驪，上殷台，西蓋馬」 | ✅ `[漢書 卷028 地理志 第八] …縣三：高句驪，《上殷台》，西蓋馬` |
| 後漢書 卷003「武陵郡兵討叛蠻」 | ✅ `[後漢書 卷003 肅宗孝章帝紀三] 九月，永昌哀牢夷叛。冬十月，《武陵郡兵》討叛蠻，破降之` |
| 後漢書 卷008「鬱林烏滸民相率內屬」 + 卷086「今烏滸人是也」 | ✅ 둘 다 실재. 추가로 讀史方輿紀要 卷110 「昔烏滸蠻所居之地」가 烏滸=蠻 을 직접 못 박는다 |
| 資治通鑑 卷070「牂柯太守朱褒、越巂夷王高定皆叛」 | ✅ 실재 (`夷` 키 근거) |

### F-2 (LOW) — 日南郡+蠻: 근거는 충분한데, 주석이 **가장 약한 인용을 골랐다**

팀 리드의 우려(「日南、象林徼外蠻夷區憐等」은 *徼外*, 즉 변경 밖의 蠻이니 郡 자체에 蠻 태그를 붙일 근거로 약하지 않은가)는 **정당한 지적이고, 그 인용에 한해서는 맞다**. 그런데 같은 corpus 에 郡 안쪽을 직접 말하는 더 강한 구절이 여럿 있다:

```
[後漢書 卷086] {{YL|永元十二年}}夏四月，日南、《象林蠻》夷二千餘人寇掠百姓，燔燒官…
[後漢書 卷004] 夏四月，日南《象林蠻》夷反，{{*|象林，縣，屬日南郡…}}
[後漢書 卷007] 冬十一月，《日南蠻》賊率衆詣郡降。
[後漢書 卷086] {{YL|建康元年}}，《日南蠻》夷千餘人復攻燒縣邑
[資治通鑑 卷052] 冬，十月，《日南蠻》夷復反，攻燒縣邑
```

`日南蠻`·`象林蠻` 이 사서에 **고유명으로 굳어 있고**, 卷004 주석이 「象林，縣，屬日南郡」라고 象林이 日南郡의 縣임을 명시한다. 유닛 이름 자체가 象林蠻兵(2166)이므로 `蠻` 을 日南郡에 붙이는 것은 **근거가 강하다**. 문제는 데이터가 아니라 주석이 하필 `徼外` 가 들어간 구절을 대표 인용으로 세웠다는 점뿐이다.

권고(비차단): `build_han_world.py` 의 `蠻` 주석에서 卷086 徼外 구절을 卷004「日南象林蠻夷反 / 象林，縣，屬日南郡」또는 卷007「日南蠻賊率衆詣郡降」으로 바꾸면 다음 사람이 같은 의심을 되풀이하지 않는다. 데이터 변경은 필요 없다.

### F-3 (LOW) — `羌` 에 `越巂郡` 추가는 게임 설계 판단이 섞여 있다

「焉出青羌與戰」은 益州牧 劉焉이 **青羌**을 동원했음을 증명하고, `青羌` 키는 이미 越巂郡에 매여 있다. 이 PR 은 그 위에 「青羌 ⊂ 羌」이라는 포함 관계를 적용해 상위 키 `羌` 에도 越巂郡을 붙였다. 사료가 「越巂에 羌이 있다」를 직접 말하진 않지만 `越巂夷王高定`(資治通鑑 卷070)·青羌 용례로 방증되며, 무엇보다 이걸 안 하면 `益州`+`羌` 을 요구하는 2147 청강이 영구 0 이다(西羌 거점은 涼州라 益州와 결코 같은 城이 아니다). **근거 강도는 "직접 증거는 없고 강한 방증"** 수준이고, 게임을 성립시키기 위한 합리적 판단으로 본다. 반대할 근거는 없다.

---

## UNKNOWN

- **U-1 (환경, PR 무관):** 이 머신의 기본 `JAVA_HOME` 은 JDK 25.0.2 이고, 그대로 `./gradlew` 를 돌리면 구성 단계에서 `* What went wrong: 25.0.2` 로 죽는다(`jvmToolchain(21)`). 이 상태에서 `build/test-results/` 의 **이전 XML 이 그대로 남아 있어** 마치 통과한 것처럼 읽힌다 — 실제로 나도 처음에 이 함정에 걸렸다(래퍼 exit 0 + 옛 XML `tests="8" failures="0"`). 이 저장소의 "exit code 를 믿지 말고 XML 을 읽어라" 규약만으로는 부족하고 **XML mtime 도 봐야 한다**. 이 PR 의 결함은 아니지만 후속 리뷰어를 위해 남긴다. 최종 수치는 XML 을 지우고 `JAVA_HOME=temurin-21` 로 다시 돌려 얻었다:
  ```
  BUILD SUCCESSFUL in 1m 14s
  logic/.../TEST-…HanGateRegionsTest.xml    tests="8" skipped="0" failures="0" errors="0"
  common/.../TEST-…UnitCatalogTest.xml      tests="9" skipped="0" failures="0" errors="0"
  ```
- **U-2:** corpus 에 水經注·東觀漢記 는 색인돼 있지 않다. 「기록에 없다」가 아니라 「이 corpus 에 없다」로만 읽어야 한다. 위 F-2/F-3 판단은 색인된 사서 범위 내 판단이다.
- **U-3:** 게임 밸런스(1 城 병종이 그 城을 잃으면 즉시 모집 불가가 되는 것)가 의도인지 여부는 데이터로 판정할 수 없다. §4 에서 보였듯 **이 지도의 기존 부속 병종 30종이 이미 그 상태**이므로 이 PR 이 새 문제를 도입한 것은 아니다. 넓힐지 여부는 제품 판단이다.

## 원복 확인

모든 뮤테이션은 실행 직후 `git checkout -- tools/scenario/build_han_world.py common infra` 로 되돌리고 `--check` 로 검증했다.

```
$ python3 tools/scenario/build_han_world.py --check
드리프트 없음.
$ git status --short
(빈 출력)
```
