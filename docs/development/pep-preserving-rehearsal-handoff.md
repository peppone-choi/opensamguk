# PEP 보존형 리허설 개발 인계

상태: 미완성 개발 WIP, `ready_for_deployment=false`.
base는 `f97dc0d2e51ca9590d9c41e9a3f27a5d20766c3e`이다.

신규 소유 경로는 `tools/ops/pep_migration.py`, `pep_migration_clone.py`,
`test_pep_migration.py`, `test_pep_migration_docker.py`,
`docs/admin/pep-preserving-migration.md` 및 이 문서다.
기존 pep-loop 구현·workflow·기존 시험은 변경하지 않았다.

`prepare`/`rehearse`만 제공하며 live apply/reset은 없다. 한 lock에서 cold capture,
전체 PG/Redis clone 복구, old/candidate app drill, 원백업 rollback clone 검증 및
같은 원본 container 재개를 조율한다. canonical PUBLIC/scenario-only 구성을 지원하고
PRIVATE/fullbundle 등 지원 밖 입력을 정지 전에 거절한다. 상세 경계는 관리자 문서를 따른다.

인계 전 실제 자체 검증: unit 20 PASS/skip0, 별도 native PG16/Redis7 synthetic 시험
1 PASS/skip0, Python compile/whitespace 검사 PASS. native 시험은 승인된 실제 V78 SQL과
저장소·Redis fingerprint/rollback을 확인했으나 실제 API·engine 이미지가 없어서
Spring/Flyway 앱 부팅·전체 앱 재개·인증 API/UI·운영 적용은 미실행이다.
초기 실패 fixture의 익명 scratch volume 귀속을 확인할 수 없어 광역 prune하지 않았다.

다음 담당: 독립 리뷰, 실제 운영 PRIVATE/fullbundle 입력 지원 검토, 정확한 이미지로 앱
부팅·DDL lock/time/space 및 backup/restore/rollback 검증, intake idle fence와 전체 서비스
재개 증거 확보. 운영 source/world/images/backup 대상 확인 전 배포 가능으로 승격하지 않는다.
수동 reset을 호출하거나 기존 자동 reset guard를 해제하지 않는다.

Python3, Docker, PostgreSQL16/Redis7이 필요하다. native 시험은 명시적 opt-in이며
synthetic fixture만 사용한다. 기본 skip 결과를 native 성공으로 보고하지 않는다.

```sh
python3 tools/ops/test_pep_migration.py
RUN_PEP_MIGRATION_DOCKER_TESTS=1 python3 tools/ops/test_pep_migration_docker.py
git diff --check
```
