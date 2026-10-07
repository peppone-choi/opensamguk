- 메타레포의 `bin/start-task`로 만든 worktree에서 작업하며 기존 변경과 비밀값을 보호한다.
- 제품 규칙은 휘하 하나다(ADR-LITE-065). 豫州 S3는 통과했고, 제품 기본 경로·pep 전환은 컷오버 순서를 따른다. 삼모 엔진·골든은 동결 회귀 기준선이다.
- [작업 경계·제품 불변식](docs/development/agent-reference.md)을 준수하고, 세부 문서·검증은 해당 작업의 절만 확인한다.
- 승인된 로컬 구현·검증은 완료까지 진행하며 commit/push/merge/deploy·데이터 변경은 해당 대상의 승인이 필요하다.
- 실제 검증 결과와 미검증·남은 위험을 보고하고, 골든·테스트·성공 근거를 날조하거나 약화하지 않는다.

## PR 루프

- 현재 head의 독립 리뷰와 필수 CI를 확인한다. Claude 또는 Codex의 토큰이 없으면 사용 가능한 쪽이 작성 세션과 분리된 독립 리뷰를 맡는다. 자기 작성 PR의 자기 판정은 금지한다.
- 독립 판정이 「머지 가능」이고 필수 CI가 초록이면 `gh pr merge --squash --auto --match-head-commit <현재 head>`로 병합한다. 관리자 우회·main 실제 RED 정지·선행 PR의 draft 조건은 유지한다.
- 승인된 수정과 관련 검증을 마치면 바로 PR을 연다. 작성 중에는 draft, 완료하면 ready로 바꾼다. [루프 명령·세션 구분](tools/pr-loop/docs/pr-review-loop.md)을 따른다.
