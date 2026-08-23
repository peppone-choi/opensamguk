# route_network_contract pytest 30건 실패 — 조사 노트 (정정판)

**작성 배경.** PR #508(work/opensamguk/han-map-wave) 작업 중 로컬 pytest 스위트에서
30건 실패가 관찰됐다. 최초 버전(이 파일의 이전 리비전)은 **내 로컬 환경에서만
재현되는 2차 증상**을 근원인으로 잘못 보고했다 — team-lead 가 신선한 체크아웃으로
재현해 그 오류를 잡았다. 이 리비전은 그 정정을 반영한다.

## 1. 원래 노트가 틀렸던 지점

- **§ "CI 에 Python 잡이 없다"는 틀렸다.** `.github/workflows/ci.yml` `agent-system`
  잡에 이미 있다(20-26행):
  ```yaml
  - name: Check provider-agnostic agent working system
    run: python3 tools/agent-system/check.py --strict --base origin/main
  - name: Verify Han map and route-node data contracts
    run: |
      python3 -m unittest discover -s tools/map/tests -p 'test_*.py'
      ...
  ```
  다만 **20번 스텝(`check.py`)이 먼저 죽으면서 22번 스텝(pytest)이 CI 에서 한 번도
  실행된 적이 없었다** — 잡이 없는 게 아니라 앞 스텝에 가려져 있었다.
- **§ "해시 상수가 도입 시점부터 틀렸다"는 틀렸다.** 아래 §3 에서 재확인.
- **§ "exit code 2 로 죽는다"는 내 로컬 환경에만 해당한다.** 내 로컬
  `data/map/`(gitignored) 에는 `external-places.json`/`han-places.json` 이 이미
  존재해서(과거 다른 작업에서 생성됨) 파이프라인이 해시 비교 단계까지 도달했다.
  **신선한 체크아웃에는 이 파일들이 아예 없다** — 진짜 1차 실패는 훨씬 앞단이다
  (§2).

## 2. 신선한 체크아웃에서 실제로 관측되는 1차 실패 — 입력 파일 부재

`git worktree add --detach` 로 신선한 체크아웃에서 재현한 결과(team-lead 확인):

```
Ran 52 tests / FAILED (failures=30, skipped=9)
AssertionError: W1-A route corridor candidate build failed:
[Errno 2] No such file or directory: '<repo>/data/map/external-places.json'
```

근원: `.gitignore:106-110`
```
data/map/*
!data/map/han-tiles.json
```
`data/map/` 아래 커밋되는 파일은 `han-tiles.json` 하나뿐이다(ADR-LITE-040 이 승인한
유일한 예외 — "게임이 서빙하는 타일맵만"). `tools/map/build_route_corridor_candidates.py:48`
의 `DEFAULT_EXTERNAL = ROOT / "data/map/external-places.json"` 을 비롯해 세 pytest
파일이 공유하는 `generate_documents()` 헬퍼가 이 경로를 필수로 요구하는데, 신선한
체크아웃엔 그 파일이 없다. 내 로컬엔 과거 CHGIS 작업에서 만든 사본이 남아 있어서
이 실패를 아예 못 봤다 — "내 환경에서만 되는 걸 CI 상태로 착각"한 전형.

세 pytest 파일(`test_route_corridor_candidates.py` / `test_route_network_contract_validation.py`
/ `test_route_network_source_validation.py`, 합계 30 실패 + 9 skipped)은 전부
같은 `generate_documents()` fixture 를 공유하므로, 이 파일 부재 하나가 30건 전체의
단일 근원인이라는 결론 자체는 여전히 유효하다 — 다만 죽는 지점이 해시 비교가
아니라 그보다 먼저인 파일 오픈이다.

## 3. `routeNodeSelection` 해시 상수 — "상수가 틀렸다"가 아니라 "파일이 나중에
정당하게 갱신됐는데 상수를 안 따라갔다"

재조사 결과, 원래 노트의 "상수가 애초부터 틀렸다"는 결론은 틀렸다. 실측:

- `tools/map/route_network_contract.py` 의 `EXPECTED_SOURCE_HASHES["routeNodeSelection"]`
  은 커밋 `fe5c5ae8`("feat(map): 한 경로망 후보 계약을 고정한다", 브랜치
  `work/opensamguk/icon-centralization`, 2026-08-23 00:59:11)에서 도입됐다.
- 그 커밋 시점의 `data/curated/han/route-node-selection-v1.json` 실제 내용을
  꺼내 sha256 을 재계산하면 **상수와 정확히 일치한다**:
  ```
  git show fe5c5ae8:data/curated/han/route-node-selection-v1.json | shasum -a 256
  → e2f2f1aec914071fbf8658ceacb099cbd9948f91766139eaa1316a87017f8c4a
  (EXPECTED_SOURCE_HASHES 상수와 동일)
  ```
  즉 **상수는 태어날 때 맞았다.**
- 그런데 그 후 `main` 에서 커밋 `59ec25eb`("feat: add reviewed Han route-node
  manifest (OPENSAM-225) (#501)", 2026-08-23 21:31:03 — `fe5c5ae8` 보다 20시간
  이상 뒤)가 **같은 파일을 처음부터 다시 물질화**했다. 커밋 메시지가 "reviewed
  manifest"라 명시하고, 실제로 리뷰 문서
  `docs/superpowers/reviews/2026-08-22-w0c-reviewed-route-node-selection-review.md`
  가 동반됐다 — 임의 수정이 아니라 검토를 거친 최종 산출물 갱신이다. 이 커밋은
  또한 `.github/workflows/ci.yml` 에 정확히 지금 문제의 그 스텝(20-26행, "Verify
  Han map and route-node data contracts")을 **처음 추가한 커밋이기도 하다** —
  즉 이 pytest 게이트 자체가 이 갱신된 파일을 검증하려고 만들어졌다.
- `git diff fe5c5ae8 HEAD -- data/curated/han/route-node-selection-v1.json` 로
  확인하면 파일이 실제로 바뀌었다(내부 provenance sha 필드들, row 구성 등). 현재
  파일의 실제 해시는 `144318023bbc3d77827a5048f0848ad400affc7e09aeecb802e4fd10d6ea290b`
  로 상수와 다르다 — 이게 원래 노트가 관측한 불일치다.
- `route_network_contract.py`(`fe5c5ae8`, `work/opensamguk/icon-centralization`
  브랜치)는 `59ec25eb`(main) 가 파일을 갱신한 뒤에도 그 브랜치 기준이라 갱신을
  몰랐고, 상수를 다시 계산해 넣은 적이 없다.

**결론(파일 vs 상수 중 어느 쪽이 틀렸는지):** 증거상 **파일이 아니라 상수가
stale 하다** — 파일은 리뷰를 거친 최신 산출물이고, 그 산출물을 검증하려고
만들어진 CI 스텝 자체가 그 갱신을 반영 못 한 오래된 해시 상수를 참조하고 있다.
다만 이 판단과 상수 갱신 실행은 이 노트의 범위가 아니다 — B1/task.md 작업과
분리된 별도 결정.

## 4. 조치 (이 노트 범위 밖, 결정 대기)

- `data/map/external-places.json`/`han-places.json` 없이 세 pytest 를 CI 에서
  돌게 하는 방법(예: 테스트가 먹는 최소 파생 픽스처를 커밋)은 **CHGIS 원본
  파생 좌표 데이터 미커밋 정책(ADR-LITE-039/040)과 충돌 가능성**이 있어 라이선스
  판단이 필요하다 — 별도로 보고.
- `EXPECTED_SOURCE_HASHES["routeNodeSelection"]` 갱신은 §3 증거를 근거로 결정할
  것을 제안하나, 실행은 이 노트의 범위가 아니다.
- CI 스텝 순서(agent-system 실패가 이후 스텝을 가리는 문제)도 별도 판단 필요.
