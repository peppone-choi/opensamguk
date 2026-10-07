# PR 루프 배포 파일

Git 저장소가 아닌 메타 허브의 루프를 PR로 검토할 수 있도록 이 경로에 배포 파일과 회귀시험을 둔다. 서버·제품 런타임을 바꾸지 않는다. [명령과 독립 리뷰 규칙](docs/pr-review-loop.md)을 따른다.

검증:

```sh
python3 -m unittest discover -s tools/pr-loop/tests -p '*_test.py'
sh -n tools/pr-loop/bin/pr-loop-watch
```

병합 후 메타 허브의 `bin/pr-loop`, `bin/pr-loop-watch`와 `docs/pr-review-loop*.md` 중 같은 이름의 파일에 반영한다. watcher 환경의 `OPENSAMGUK_META_ROOT`는 운영 허브를 가리켜야 한다. `tests/pr_loop_test.py`도 동일하게 옮긴다. 원격 PR 판정과 병합 상태를 확인하기 전에는 활성 watcher를 교체하지 않는다.
