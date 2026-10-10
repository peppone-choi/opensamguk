# PR 루프 배포 파일

Git 저장소가 아닌 메타 허브의 루프를 PR로 검토할 수 있도록 이 경로에 배포 파일과 회귀시험을 둔다. 서버·제품 런타임을 바꾸지 않는다. [명령과 독립 리뷰 규칙](docs/pr-review-loop.md)을 따른다.

검증:

Work-unit PR-A는 report/DRY_RUN/UNBOUND 기본값이다. 실제 META 활성화 상태가 아니다.
[계약·도입·증거 단계](../../docs/development/work-unit-enforcement.md)를 확인한다.
기존 bin 5개 외에 bin/work-queue, bin/work-complete, lib/work_units/*.py,
tests/work_units_test.py와 갱신한 lifecycle/review 시험·문서를 함께 배포해야 한다.
product repository의 work-units/binding.json과 surface-map/units/gaps 데이터도 필요하다.
복사 누락은 실패해야 하며 head package나 임의 PYTHONPATH로 보충하지 않는다.
설치 bundle hash와 dry-run probe/watcher 로그를 확인하기 전에는 SOURCE_ONLY/PENDING_DEPLOYMENT다.

```sh
python3 -m unittest discover -s tools/pr-loop/tests -p '*_test.py'
sh -n tools/pr-loop/bin/pr-loop-watch tools/pr-loop/bin/start-task tools/pr-loop/bin/finish-task
```

병합 후 C0 조율로 watcher를 한 tick 쉬게 한 뒤 메타 허브의 `bin/pr-loop`, `bin/pr-loop-watch`, `bin/task-lifecycle`, `bin/start-task`, `bin/finish-task`와 `docs/pr-review-loop*.md` 중 같은 이름의 파일에 반영한다. `tests/pr_loop_test.py`도 동일하게 옮긴다. 배포된 실행 파일의 실행 권한과 watcher 환경의 `OPENSAMGUK_META_ROOT`·`PR_LOOP_STATE`를 확인하고 watcher를 재개한다. 원격 PR 판정과 병합 상태를 확인하기 전에는 활성 watcher를 교체하지 않는다.

새 `start-task`는 **기존 worktree와 branch가 모두 없는 경우에만** 작업을 먼저 예약하고, Git worktree 생성 뒤 등록을 활성화한다. 재사용·기존 branch는 자동 소유로 승격하지 않는다. `CODEX_THREAD_ID`를 기본 작성 세션으로 기록하며 `OPENSAMGUK_TASK_OWNER_SESSION`으로 명시할 수 있다. 기록된 세션은 PR 본문의 `pr-loop v2 author-session`과 일치를 확인한다. 세션 문자열과 로컬 nonce는 로컬 출처의 상관키이며 암호적 인증이 아니다.

watcher는 열린 PR을 보는 기존 리뷰 루프와 독립적으로 등록된 작업의 PR을 `state=all`로 조회한다. 유일한 같은 repo/branch PR의 실제 `merged=true`·`merged_at`·head가 확인되고, 해당 local head가 PR head의 조상이며, 등록 path/branch와 무변경 작업트리·잠금 부재가 확인되어야 정리한다. PR 본문의 `교훈:` 1–3줄 또는 실제 PR 제목·수정 파일에 근거한 짧은 교훈을 메타 `reports/<project>/lessons/`에 먼저 영구 기록한다. 그 다음 일반 `git worktree remove`, 검산된 ref에 대한 CAS `git update-ref -d`만 실행한다. 원격 branch는 삭제하지 않는다. 단계 기록으로 중간 실패 후 재개하며, 미등록 작업·dirty WIP·다른 작업·잠금·불일치 작업은 남긴다. 수동 `finish-task <project> <task>`도 같은 관문을 통과해야 한다.

등록 시 BOARD에 project/task/nonce 전용 표식이 있는 자체 진행 행 하나를 만든다. 유일한 PR을 찾으면 그 표식에 PR 번호를 묶고, local ref 삭제까지 끝난 후 그 행 하나만 지운다. 같은 PR을 언급한 공유 행·다른 레인 행은 건드리지 않는다. 표식이 없거나 중복되거나 BOARD 쓰기가 실패하면 다른 행을 추정해 지우지 않고 단계 기록을 남겨 다음 tick에서 재개한다.

main CI에서 확인한 실패는 `PR_LOOP_STATE/main-ci-red.json` 한 파일에 보존한다. 재실행 대기·취소·조회 오류·새 main의 검증 대기는 이 실패를 지우지 않는다. 재실행으로 run 요약이 바뀌면 이전 attempt도 확인하며, 실패 head와 같거나 검증한 후손의 완료된 성공만 정지를 해제한다. 아직 실패를 확인하지 않은 PENDING/UNKNOWN은 새 RED를 만들지 않는다. 동시 조회는 관측 파일의 짧은 잠금으로 직렬화한다.
