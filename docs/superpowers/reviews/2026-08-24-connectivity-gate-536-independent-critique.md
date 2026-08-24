# #536 연결성 게이트 독립 비평 — 커밋 7f5f57f2

Scope: commit 7f5f57f29890726dc12ae9315069b68a733914fb on work/opensamguk/tiles-tier-524, single file tools/map/tests/test_han_tiles_adjacency_connectivity.py

Verdict: cleared

검증자: critic-conn-536 (독립). 전용 워크트리 `scratchpad/wt-conn536` 를 `git worktree add --detach 7f5f57f2` 로 새로 파서 실행했다. 제출자 워크트리(`worktrees/opensamguk/tiles-tier-524`)는 읽기만 했다 — 마지막 절에 무결성 증거를 붙였다.

---

## E. 커밋 위생 — 통과

```
$ git show --stat 7f5f57f29890726dc12ae9315069b68a733914fb
 .../tests/test_han_tiles_adjacency_connectivity.py | 74 ++++++++++++++++++++++
 1 file changed, 74 insertions(+)

$ git diff --name-only 7f5f57f2^ 7f5f57f2 -- data/
(빈 출력)
```

`data/` 아래는 한 바이트도 안 건드렸다. 최악의 사고(재생성본 동반 커밋)는 없다.

blob 동일성도 확인했다 — 커밋 트리 · 제출자 워크트리 작업본 · 내 워크트리 세 곳 모두:

```
$ git rev-parse 7f5f57f2:data/map/han-tiles.json
3ebef25f2c206b99ac802e58b6dfcf5aadcce8db
$ git hash-object <제출자 워크트리>/data/map/han-tiles.json
3ebef25f2c206b99ac802e58b6dfcf5aadcce8db
```

제출자 워크트리 `git status --short` 는 빈 출력이다. 실험 후 원복했다는 보고는 사실이다.

## A. 공허한 GREEN 이 아닌가 — 통과 (재현 검증)

### A-1. 실제 재생성으로 직접 RED 를 냈다 (제출자 스냅샷 사용 안 함)

제출자 보고를 근거로 쓰지 않기 위해, gitignored 입력(`junguozhi.json`·`han-places.json`·`terrain-grid.json`·`readings.json`·`external-places.json`)을 제출자 워크트리에서 **복사만** 해서 내 워크트리에 넣고 `build_tile_grid.py` 를 직접 돌렸다.

```
$ python3 tools/map/build_tile_grid.py
data/map/han-tiles.json · 0.4MB · cities 1145 · seats 174 · regions 38 · adjCounty 1230 · adjCommandery 366 · ...

$ (연결성 측정) n=1145 edges=1230 components=356 main=660 ratio=0.5764 isolated=321

$ python3 -m unittest discover -s tools/map/tests -p 'test_*.py'
AssertionError: 0.5764192139737991 not greater than or equal to 0.98 : 연결 성분 356개 · 최대 성분 660/1145(57.6%) · 고립 노드 321개. ... 이 파일은 재생성 금지다 — git checkout 으로 커밋본을 되돌려라.
Ran 44 tests in 0.022s
FAILED (failures=1, skipped=10)
```

제출자가 보고한 1230/356/57.6%/321 숫자와 정확히 일치한다. **커밋 메시지의 RED 주장은 재현된다.**

부수 소득: `failures=1` 이다. 재생성본 앞에서 **기존 43개 테스트는 전부 초록**이었다. 이 게이트가 없으면 재생성 커밋은 CI 를 그대로 통과한다 — 순수 신규 커버리지다.

(주의: `build_terrain_grid.py` 는 `data/natural-earth/` 가 필요한데 gitignored 라 내 워크트리에 없다. 그래서 파이프라인 **마지막 단계**만 독립 재실행했다. `terrain-grid.json` 자체를 재생성했을 때의 수치는 UNKNOWN 이다 — 다만 그 입력이 이미 제출자의 from-scratch 산출물이라 결론은 바뀌지 않는다.)

### A-2. 무작위 간선 제거 스윕 — 임계값이 "아무 데나 찍은 숫자"인가?

원본을 격리 샌드박스(`lab/root/`, 같은 테스트 파일 + 변조된 `data/map/han-tiles.json`)에 복제해 seed 고정(1234) 무작위 제거를 돌렸다.

| 제거율 | edges | components | main ratio | isolated | 결과 |
|---|---|---|---|---|---|
| 2% | 2616 | 12 | 98.78% | 10 | OK |
| 5% | 2532 | 12 | 98.78% | 10 | OK |
| 10% | 2413 | 12 | 98.78% | 10 | OK |
| 12% | 2367 | 13 | 98.69% | 11 | OK |
| 14% | 2313 | 13 | 98.69% | 11 | OK |
| 16% | 2250 | 13 | 98.69% | 11 | OK |
| 18% | 2205 | 14 | 98.60% | 12 | OK |
| **20%** | 2161 | 15 | 96.16% | 12 | **FAILED** |
| 30% | 1929 | 22 | 95.55% | 18 | FAILED |
| 50% | 1385 | 88 | 87.34% | 66 | FAILED |

경계는 18%~20% 사이다. 즉 **간선 500개 가까이 무작위로 날려도 통과한다.** 이건 임계값이 헐거워서가 아니라 그래프가 그만큼 중복(redundant)해서다 — 18% 를 날려도 연결성은 실제로 유지된다. 불변식이 "간선 수"가 아니라 "연결성"인 이상 이건 정의상 정상이다. 다만 **간선 수 자체는 전혀 보호되지 않는다**는 사실은 F 절에 경계로 명시한다.

### A-3. 부분적 파손 — 큰 성분은 멀쩡하고 도시 N개만 고립

각 희생 노드의 **모든** 간선을 제거해 강제 고립시켰다.

| 고립시킨 도시 | components | main ratio | isolated | 결과 |
|---|---|---|---|---|
| 2 | 14 | 98.60% | 12 | OK |
| 3 | 15 | 98.52% | 13 | OK |
| **4** | 16 | 98.43% | **14** | OK (턱걸이) |
| **5** | 17 | 98.34% | **15** | **FAILED** |
| 8 | 20 | 98.08% | 18 | FAILED |
| 20 | 33 | 96.94% | 31 | FAILED |

**요청받은 "도시 20개만 고립" 시나리오는 잡힌다.** 실제 실패 메시지:

```
AssertionError: 0.9694323144104804 not greater than or equal to 0.98 : 연결 성분 33개 · 최대 성분 1110/1145(96.9%) · 고립 노드 31개. ...
```

(20개를 고립시키면 그 이웃까지 딸려 나가 실제 고립은 31개가 된다.)

민감도 하한은 **도시 5개**다. 4개까지는 통과한다.

### A-4. 정당한 개선에는 안 죽나 — 통과

SEA_LINKS 이식(#529) 형태를 흉내내 현재 고립 노드 5개를 본 성분에 이어 붙였다(간선 +5).

```
improve(+5 edges): n=1145 edges=2667 components=7 main=1136 ratio=0.9921 isolated=5  -> OK
```

고립이 줄고 간선이 느는 방향은 여유롭게 통과한다. 이 게이트는 개선을 막지 않는다.

## B. 임계값의 정당성 — 조건부 통과 (실제 여유는 보고보다 좁다)

커밋본 실측: components 12 · main 1131/1145(98.777%) · isolated 10.

| 조건 | 임계값 | 현재값 | 여유 |
|---|---|---|---|
| `main_ratio >= 0.98` | main ≥ 1123 (0.98×1145=1122.1) | 1131 | **도시 8개** |
| `isolated < 15` | ≤ 14 | 10 | **도시 4개** |
| `len(sizes) < 20` | ≤ 19 | 12 | 성분 7개 |

**의뢰 전제에 대한 정정: 병목은 98% 비율이 아니라 `MAX_ISOLATED` 다.** 0.78%p 여유는 도시 8개에 해당하지만, 도시가 하나씩 고립될 때는 `isolated` 가 먼저 15에 닿는다(A-3 표: 5개째에서 실패, ratio 는 그때 98.34% 로 아직 여유 있음). 실질 여유는 **도시 4개**다.

이게 위험한가? 두 방향으로 봤다.

- **정당한 변경이 이걸 밟을 위험**: #529 SEA_LINKS 는 고립을 14→9 로 **줄이는** 방향이라 안전하다(A-4 확인). 위험한 건 "縣 을 새로 추가하는데 인접을 안 달아주는" 변경이다 — 5개 이상 한꺼번에 추가하면 정당해도 RED 가 난다. 다만 좌표 없이 인접 없는 縣 을 5개 넣는 건 그 자체로 검토 대상이라, 이 RED 는 오탐이라기보다 유의미한 알림에 가깝다.
- **여유가 너무 넓은가**: 아니다. A-3 이 보여주듯 5개 고립에서 이미 잡히고, 실제 방어 대상인 재생성(321 고립)은 21배 초과다.

결론: 임계값 셋 다 실측에 근거한 값이고 "아무 데나 찍은 숫자"가 아니다. 다만 주석(19행)의 "SEA_LINKS 등 정당한 개선으로 고립이 14→9 로 줄어드는 정도는 넉넉히 통과하는 여유를 남긴다"는 표현은 **줄어드는 방향의 여유**만 말하고 늘어나는 방향 여유가 4개뿐이라는 걸 안 적는다. 차단 사유는 아니지만 후속 변경자가 오해할 수 있는 지점이다.

## C. CI 에서 실제로 도는가 — 통과

`.github/workflows/ci.yml:27` 의 스텝을 그대로 실행:

```
$ python3 -m unittest discover -s tools/map/tests -p 'test_*.py' -v
...
test_county_adjacency_stays_connected (test_han_tiles_adjacency_connectivity.HanTilesAdjacencyConnectivityTest.test_county_adjacency_stays_connected) ... ok
...
Ran 44 tests in 0.042s
OK (skipped=10)
```

- 파일명 `test_han_tiles_adjacency_connectivity.py` 는 `test_*.py` 패턴에 잡힌다 — verbose 출력에 `... ok` 로 실제 실행됐음이 찍힌다.
- **skip 이 아니다.** `skipped=10` 은 전부 `test_junguozhi_contract` 의 기존 스킵이며(`source-refresh-only: fetch gitignored HHS corpus with tools/corpus/fetch_sources.py`), 부모 커밋에서도 동일하게 10개다.
- 소스 스캔: `grep -nE "skip|setUp|tearDown|write|open\(|os\.environ|global|exists\(\)|try:"` 결과 코드에는 한 건도 없고 2행 주석의 "skip" 단어 하나만 걸린다. **존재 검사·조건부 skip·try 분기가 없다.** 파일이 없으면 `FileNotFoundError` 로 죽는다 — 조용히 통과할 경로가 없다.
- 스텝에 `if: '!cancelled()'` 가 붙어 있어 앞 스텝 실패에 가려지지도 않는다.

## D. 회귀 — 통과

```
$ git checkout 7f5f57f2^ && python3 -m unittest discover -s tools/map/tests -p 'test_*.py'
Ran 43 tests in 0.009s
OK (skipped=10)

$ git checkout 7f5f57f2 && python3 -m unittest discover -s tools/map/tests -p 'test_*.py'
Ran 44 tests in 0.042s
OK (skipped=10)
```

43 → 44, 스킵 수 불변, 실패 없음. 신규 테스트는 `TILES.read_text()` 한 번만 하고 파일을 쓰지 않으며 `setUp`/`tearDown`/전역 상태/환경변수를 건드리지 않는다 — 오염 경로 없음. 순수 함수 `_components` 는 모듈 수준 가변 상태가 없다. 수행시간 +0.03초.

## F. 이 게이트가 정말 목적을 달성하나 — 부분적으로. 경계를 정확히 적는다

**보호하는 것 (실측 확인)**
- 누군가 `build_tile_grid.py`(및 그 상류)로 `han-tiles.json` 을 재생성해 커밋하면 CI 가 죽는다. A-1 에서 직접 재현했다. 기존 43개 테스트는 이걸 못 잡는다.
- 縣(`adjacency.county`) 그래프에서 도시 5개 이상이 고립되는 모든 파손.
- 간선 20% 이상이 연결성을 깨는 방식으로 사라지는 파손.

**보호하지 못하는 것 (실측 확인 — 과대평가 금지)**
- **`adjacency.commandery` 는 전혀 안 본다.** 재생성이 郡 인접을 425→366 으로 깎았는데 테스트는 이 필드를 읽지도 않는다. 실증:
  ```
  county 그대로 + commandery=[] + terrain="" + owner/regions/juns/seatOwner 전부 비움
  -> n=1145 edges=2662 components=12 ratio=0.9878 isolated=10  -> OK
  ```
  즉 **縣 인접만 살아 있으면 지형 격자·소유 격자·郡 인접·지역·치소가 통째로 날아가도 초록이다.**
- **간선 수 자체가 불변식이 아니다.** 연결성을 유지한 채 간선 18%(≈480개)가 사라지면 통과한다(A-2). 재생성이 **우연히 연결성을 유지하면서** 간선/지형/소유를 망가뜨리는 경우 이 게이트는 침묵한다.
- `cities` 배열 자체(개수·순서·좌표)를 검증하지 않는다. 인덱스 의미가 통째로 밀려도 `a`/`b` 가 범위 안이고 연결돼 있으면 통과한다.
- **근본 원인을 고치지 않는다.** #536 의 파편화 원인은 여전히 UNKNOWN 이고, 이 커밋은 "옳은 파일을 만드는 법"을 복원하지 않는다. 저장소는 여전히 **재현 불가능한 산출물**을 커밋해 들고 있다. 이 게이트는 그 상태를 고치는 게 아니라 **더 나빠지는 걸 막는 래칫**이다.

이 경계는 커밋이 스스로 주장하는 범위("인접 그래프 파편화를 CI 에서 막는다")와 일치한다. 커밋 메시지도 원인 UNKNOWN 을 숨기지 않는다. 과대주장 없음.

## 남은 제안 (차단 아님)

1. `MAX_ISOLATED` 여유가 실질 4개라는 점을 20~22행 주석에 적으면 후속 변경자가 덜 놀란다.
2. 같은 union-find 로 `adjacency.commandery` 도 한 줄 더 검사하면 郡 그래프 블라인드가 닫힌다. 이번 커밋 범위 밖이므로 후속 이슈로 충분하다.

## 워크트리 무결성

```
$ cd <내 워크트리> && git status --short --branch
## HEAD (no branch)
$ git hash-object data/map/han-tiles.json
3ebef25f2c206b99ac802e58b6dfcf5aadcce8db
$ python3 -m unittest discover -s tools/map/tests -p 'test_*.py'
Ran 44 tests in 0.038s
OK (skipped=10)

$ cd <제출자 워크트리> && git status --short --branch
## work/opensamguk/tiles-tier-524...origin/work/opensamguk/tiles-tier-524
$ git hash-object data/map/han-tiles.json
3ebef25f2c206b99ac802e58b6dfcf5aadcce8db
```

재생성 실험 후 `git checkout -- data/map/han-tiles.json` 으로 원복했고 복사해 온 gitignored 입력도 전부 삭제했다. 두 워크트리 모두 깨끗하다. 커밋·푸시·PR 없음.

## UNKNOWN 으로 남기는 것

- `data/natural-earth/` 가 없어 `build_terrain_grid.py` 부터의 전체 재생성은 못 돌렸다. 그 경로의 수치는 UNKNOWN.
- 커밋본 `han-tiles.json` 을 만든 원래 조합이 무엇이었는지는 이 검증에서도 밝히지 못했다 — #536 의 원인은 여전히 UNKNOWN.
- GitHub Actions 러너에서의 실제 실행은 로컬 재현으로 대체했다. 원격 CI 로그는 확인하지 않았다.
