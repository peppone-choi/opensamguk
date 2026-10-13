# PR #1603 F1 수정 인계

- taskId: opensamguk-pr1603-f1-resume-20261013-0815
- project: opensamguk
- stage: 지적 수정 (F1)
- repository: https://github.com/peppone-choi/opensamguk
- branch: buzz/opensamguk-pr1603-f1-resume-20261013-0815/opensamguk-fix
- cwd: /srv/openclaw-dev/company/agents/opensamguk-fix/worktrees/opensamguk-pr1603-f1-resume-20261013-0815
- 시작/원격 확인 head: 930a2f30c1d4acd00fb3cff8260a0f9bda43261f
- PR 원격 브랜치: buzz/opensamguk-dispatch-evidence-implement-20261013-0727/opensamguk-implement
- commit: 본 인계서와 시험 수정이 포함된 커밋 (정확한 SHA는 native completion 인계에 기록)

## 실제 산출물

`tools/ci/test_input_evidence_gate.py` 및 본 인계서만 변경.

- 합성 UI 루트에 CourtHandler.kt / DispatchInputBoundaryTest.kt 증거 파일을 복사.
- InputEvidenceGateTest.setUp은 두 발령 행 evidence만 {}로 복원. deliveryState 유지, 기존 합성 45 단언 유지.
- 실제 ROOT의 5개 부채 단언만 43으로 갱신.
- 전용 시험 추가: 두 HANDLER_READY 상태와 정확한 DOMAIN/HANDLER 참조, ROOT _proof 역할, 증거 없는 임시 루트의 거절, 증거만 되돌린 카탈로그의 45건과 check()의 43건 사이 제거 집합이 정확히 court.dispatch / court.dispatchReply임을 검증. 나머지 부채 행도 완전히 동일함을 단언.
- 데이터, 게이트 본체, Kotlin, 다른 담당 산출물은 수정하지 않음. 기존 시험 삭제 없음.

## 검증 근거

- 최초 모듈 실행: TypeScript 의존성 부재로 실패. 제품 결함으로 세지 않음.
- `npm --prefix tools/ci ci --ignore-scripts`: PASS (1 package, 0 vulnerabilities).
- `python3 -m unittest discover -s tools/ci -p test_input_evidence_gate.py`: PASS, 84 tests / 140.452s / OK. 로그: /tmp/opensamguk-pr1603-f1-test.log.
- `git diff --check`: PASS.
- 작업 시작 GitHub PR #1603: OPEN / 지정 head 일치. #892: OPEN.

## 다음 담당 / 경계

- 다음 담당: opensamguk-verify.
- 새 PR head의 hosted CI tools green 및 game-engine DispatchInputBoundaryTest 6 PASS / 0 skip 확인 필요.
- Kotlin/전체 무거운 시험, 독립 재리뷰, 제품 QA: NOT_RUN (본 수정 담당 범위 밖).
- 병합/운영 배포/GCP/게임 초기화: NOT_RUN.
- blocker: 수정 작업 blocker 없음. 후속 hosted CI tools/game-engine 검증은 미확정.
