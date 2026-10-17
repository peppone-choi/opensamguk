# 지침 파일 검사

에이전트 지침 파일(`AGENTS.md`·`CLAUDE.md`·`.claude/rules/`와 루트가 직접 링크한 문서)을 가볍게 검사한다. 표준 라이브러리만 쓴다.

```bash
python3 tools/instructions/check_instructions.py            # 저장소 루트 자동 인식
python3 tools/instructions/check_instructions.py --strict   # WARN도 실패로 본다
cd tools/instructions && python3 -m unittest test_check_instructions
```

- 입력: git이 추적하거나 무시되지 않은 새 파일, `protected-rules.tsv`(id·파일·정확한 문구, 탭 구분).
- 출력: 지침 파일별 줄 수, `WARN`/`FAIL` 줄, 마지막 줄 `RESULT PASS|FAIL`. `FAIL`이면 종료 코드 1이다.
- `FAIL`: 파일당 200줄 초과, 링크·`@path` import 대상 없음, 규칙 `paths`가 어떤 파일과도 맞지 않음, 보호 문구가 지정 파일에서 사라짐, 보호 목록 파일이 없거나 유효한 행이 0개, `AGENTS.md` 묶음이 Codex 기본 한도 32KiB 초과.
- `WARN`: 백틱으로 적은 저장소 경로가 없음(다른 저장소 경로·과거 기록일 수 있어 사람이 판단한다).
- CI·브랜치 보호에는 연결하지 않았다. 연결은 별도 제안과 승인으로 한다.
- 운영 규칙은 `.claude/rules/instruction-files.md`에 있다.

## 다른 저장소에서 쓰기

1. `check_instructions.py`를 복사하고, 그 저장소의 핵심 안전·승인·시험·병합 규칙 문구로 `protected-rules.tsv`를 만든다.
2. `--root`(저장소 루트)와 `--manifest`(목록 경로)를 넘기거나 같은 위치(`tools/instructions/`)에 둔다.
3. 그 저장소 `AGENTS.md`에 "작업 시작과 완료 때 이 검사를 돌리고 결과를 보고한다"는 한 줄을 넣는다. Codex는 `AGENTS.md`만 자동으로 읽으므로 이 한 줄은 `AGENTS.md`에 있어야 한다.
