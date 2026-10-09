# Main push 빌드 전용 전환 인계

상태: 미완성 개발 WIP 인계이며 운영 적용 승인이 아니다. base는
`3109067b021ac948331e926791d44aac5ffda4a8` (#1565 implicit reset guard)이다.

`deploy.yml`은 `apply_changes=false`로 정확한 main CI admission과 불변 이미지 빌드만
요청한다. 재사용 `pep-loop.yml`의 boolean 기본값도 false이며 runtime apply job 전체를
막는다. 수동 `pep-refresh.yml`/`pep-reset.yml`은 true를 명시한다. 이미지별 계약과
source/image `candidate.json`을 artifact로 남긴다. 수동 reset의 기존 승인·복구 gate를
새로 강화한 구현은 아니므로 실제 삭제·배포를 승인하지 않는다.

소유 경로: `.github/workflows/{deploy,pep-loop,pep-refresh,pep-reset}.yml`,
`tools/ops/test_pep_workflow_staging.py`, `docs/admin/game-server-recovery.md` 및 이 문서.
`pep_loop.py`와 보존형 리허설 구현은 별도 변경 단위다.

인계 전 실제 자체 검증: focused 4 PASS, 기존 pep-loop 73 PASS, scenario 3 PASS,
whitespace 검사 PASS. 각 실행 결과를 합산하지 않는다. Python3와 PyYAML 6.0.3을 사용했다.
actionlint 및 GitHub에서 이 변경본의 workflow 실행은 미실행이다. 실제 서버 작업은 없다.

다음 담당은 `pep-loop-test.yml`에 focused 시험을 연결하고, 변경 부분 독립 리뷰 및
정확한 새 head CI를 확인한다. #1565와의 전달·통합 방식을 정한 뒤 main push가 runtime을
변경하지 않는지 확인한다. 안전한 배포 경로 확인 전 main merge/trigger를 실행하지 않는다.
실제 candidate image·VM·backup/restore/rollback 검증은 별도 작업이다.

재현 명령:

```sh
python3 tools/ops/test_pep_workflow_staging.py
python3 tools/ops/test_pep_loop.py
python3 tools/ops/test_pep_scenarios.py
git diff --check
```
