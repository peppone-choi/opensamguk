# route_network_contract pytest 30건 실패 — 조사 노트

**작성 배경.** PR #508(work/opensamguk/han-map-wave) 작업 중 로컬 pytest 스위트에서
30건 실패가 관찰됐다. team-lead 지시: **지금 고치지 마라** — 조사만 남기고 별도
티켓/skip-guard CI 레인(Python 잡을 CI 가드에 추가하는 작업)으로 넘긴다. 이 문서는
그 근거 자료다.

## 1. 실패 목록 (파일별)

venv: `/tmp/mapcheck-venv` (PEP 668 externally-managed 시스템 파이썬 우회).

| 파일 | 실패 수 |
|---|---|
| `test_route_corridor_candidates.py` | 13 |
| `test_route_network_contract_validation.py` | 5 |
| `test_route_network_source_validation.py` | 5 top-level + 7 subTest = 12 |
| **합계** | **30** |

세 파일 모두 `tools/map/build_route_corridor_candidates.py` / `tools/map/route_network_contract.py`
를 exercise 한다. 세 파일의 실패는 전부 같은 fixture(`generate_documents()`, CLI 를
서브프로세스로 호출)가 **exit code 2** 로 죽으면서 실제 테스트 로직에 도달하기 전에
전부 abort 되는 단일 원인이다 — 30건이 30개의 독립된 결함이 아니라 **결함 1개가
30개 테스트 케이스로 퍼진 것**이다.

## 2. 실제 원인 — 해시 상수 드리프트 (근원인 단일성)

`tools/map/route_network_contract.py` 의 `EXPECTED_SOURCE_HASHES` (line 17-22):

```python
EXPECTED_SOURCE_HASHES: Final = {
    "legacyHanMap": "2a2cd0c5813bbdd037c0cad41dc2ccd34c582830aacadb1ad8c135985f4d3a58",
    "routeNodeSelection": "e2f2f1aec914071fbf8658ceacb099cbd9948f91766139eaa1316a87017f8c4a",
    "legacyExternalPlaces": "33cd7fbc2068b0552bc557e879ada0230596365f440db1719133a2dae05d20fe",
    "scenarioCatalogService": "e0a60532dcd47c7d8fd222aa153d03a73b381733be686ed795fc4be09c1b8f7c",
}
```

sha256 실측 대조:

| 키 | 상수 | 실제 파일 해시 | 일치 |
|---|---|---|---|
| `legacyHanMap` (han.json) | `2a2cd0c5...4d3a58` | 동일 | ✅ |
| `routeNodeSelection` | `e2f2f1ae...c4a` | `144318023bbc3d77827a5048f0848ad400affc7e09aeecb802e4fd10d6ea290b` | ❌ |
| `legacyExternalPlaces` | `33cd7fbc...5d20fe` | 동일 | ✅ |
| `scenarioCatalogService` | `e0a60532...4be09c1b8f7c` | 동일 | ✅ |

**`routeNodeSelection` 하나만 어긋난다.** 4개 중 3개는 정상이다 — 이번 PR 의 F3
작업(han.json/HanCityConst.kt/HanGateIndex.kt 재작성)이 원인이 **아니다**
(`legacyHanMap` 이 정확히 이 세 파일을 검증하는 키인데 그것부터 일치한다).

파일 내용 자체는 브랜치와 `origin/main` 사이에 바이트 단위로 동일하다
(`git diff origin/main -- data/curated/han/route-node-selection-v1.json` 결과 없음).
즉 이 상수는 **파일이 바뀐 게 아니라 상수 자체가 애초에 틀렸다.**

## 3. `origin/main` 대조 — 내 작업의 회귀가 아니다

```
git worktree add /tmp/wt-main origin/main
```

로 확인한 결과, `route_network_contract.py` / `build_route_corridor_candidates.py`
와 이 셋을 exercise 하는 pytest 파일 3종은 **`origin/main` 에 아예 존재하지 않는다**.
main 에는 `test_administrative_place_overlay.py` / `test_junguozhi_contract.py`
만 있고 (18 passed / 9 skipped) 이 30건 실패 스위트와는 무관하다.

`git log --oneline --follow -- tools/map/route_network_contract.py` → 단일 커밋
`fe5c5ae8` ("feat(map): 한 경로망 후보 계약을 고정한다") 에서 도입된 뒤 한 번도
수정된 적 없다. `fe5c5ae8` 는 F1 의 대상 커밋(`89e06e3c`)과 무관한 이전 커밋이다.

**결론: `routeNodeSelection` 해시 상수는 도입 시점부터 실제 파일과 맞은 적이
없다.** 즉 이 코드는 "한 번도 green 이었던 적 없이 커밋된" 케이스다 — 앞서
Docker-skip 이 false-green 을 만든 사례(`87737e2a`)와 같은 계열의 문제지만
독립적인 두 번째 사례다. 30건 실패는 PR #508 의 회귀가 아니라 **선재 결함**이다.

worktree 는 확인 후 `git worktree remove /tmp/wt-main --force` 로 제거했다.

## 4. 왜 지금까지 안 잡혔나

CI 에 Python 테스트 잡이 없다 — 이게 이 상수가 처음부터 틀린 채로 병합될 수 있었던
조건이다. skip-guard 레인(현재 별도로 구축 중인 CI 가드)이 이 사례를 Python CI
게이트 부재의 실증 예시로 쓸 수 있다.

## 5. 후속 조치 (이 노트 범위 밖)

- `EXPECTED_SOURCE_HASHES["routeNodeSelection"]` 을 실제 파일 해시로 갱신하거나,
  파일이 잘못됐다면 파일을 고치는 결정은 이 PR 의 스코프가 아니다 — 별도 티켓.
- CI 에 Python 테스트 스텝을 추가하는 작업은 skip-guard 레인에서 진행 중.
