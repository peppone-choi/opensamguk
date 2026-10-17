---
paths:
  - "data/map/**"
  - "tools/map/**"
---

# han-tiles 결합 산출물

[작업 참고](../../docs/development/agent-reference.md)에서 옮긴 경로별 규칙이다. 경로와 명령은 저장소 루트 기준이다. Codex는 자동으로 읽지 않으므로 작업 참고 표를 보고 직접 읽는다. 이 규칙은 명령 실행 승인을 대신하지 않는다.

## han-tiles 를 바꾸는 PR (GH #818)

`data/map/province-tiles.json`·`han-world-v3.json` 을 바꾸면 거기에 묶인 커밋 산출물이 낡는다. 결합 목록과 재생성 명령의 정본은 `tools/map/check_map_inputs.py` 다.

- [ ] `python3 tools/map/check_map_inputs.py --regenerate` 를 돌리고 바뀐 산출물을 같은 PR 에 넣는다.
- [ ] `python3 tools/map/check_map_inputs.py --check --include-slow` 출력을 PR 본문에 붙인다. 「사람 판정」 항목이 STALE 이면 지목된 원장·노트를 검토해 고친다.
- [ ] 머지 직전에 main 을 다시 합쳐 한 번 더 돈다 — 각 PR 의 CI 는 제 merge ref 에서만 초록이라, 거의 동시에 머지되는 타일 PR 끼리는 서로를 못 본다.
- han-tiles 를 읽는 `--check` 도구를 새로 만들면 목록에 넣는다(안 넣으면 `test_check_map_inputs.py` 가 빨개진다).
