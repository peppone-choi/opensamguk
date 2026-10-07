# PR 루프 배포 파일

Git 저장소가 아닌 메타 허브의 루프를 PR로 검토할 수 있도록 이 경로에 배포 파일과 회귀시험을 둔다. 서버·제품 런타임을 바꾸지 않는다. [명령과 독립 리뷰 규칙](docs/pr-review-loop.md)을 따른다.

검증:

```sh
python3 -m unittest discover -s tools/pr-loop/tests -p '*_test.py'
sh -n tools/pr-loop/bin/pr-loop-watch
```

병합 후 메타 허브의 `bin/pr-loop`, `bin/pr-loop-watch`와 `docs/pr-review-loop*.md` 중 같은 이름의 파일에 반영한다. watcher 환경의 `OPENSAMGUK_META_ROOT`는 운영 허브를 가리켜야 한다. `tests/pr_loop_test.py`도 동일하게 옮긴다. 원격 PR 판정과 병합 상태를 확인하기 전에는 활성 watcher를 교체하지 않는다.

main CI에서 확인한 실패는 `PR_LOOP_STATE/main-ci-red.json` 한 파일에 보존한다. 재실행 대기·취소·조회 오류·새 main의 검증 대기는 이 실패를 지우지 않는다. 재실행으로 run 요약이 바뀌면 이전 attempt도 확인하며, 실패 head와 같거나 검증한 후손의 완료된 성공만 정지를 해제한다. 아직 실패를 확인하지 않은 PENDING/UNKNOWN은 새 RED를 만들지 않는다. 동시 조회는 관측 파일의 짧은 잠금으로 직렬화한다.
