# PEP 보존형 리허설 개발 인계

상태: 기능 QA 우선 전환에 따라 보류한 개발 체크포인트, `ready_for_deployment=false`.
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

2026-10-09 후속 체크포인트는 PRIVATE validation consumer가 PUBLIC와 혼재하거나 정지된 채 남아도
첫 정지 전에 거절하며, 매 clone 생성 전 backup payload/manifest/env를 다시 검증한다.
각 앱 단계 전후와 재개 직전 scenario 동반 사본·원본 bytes를 대조하고, 정지 전 원본 Redis의
16 DB 값·absolute expiry fingerprint를 cold clone·원본 storage 재개와 연결한다.
Redis 값이 바뀌면 key 수가 같아도 API/engine을 재개하지 않는다. 종료된 clone 앱의 health polling은 중단한다.

PRIVATE consumer 배치와 topdown fullbundle, storage recovery bundle은 서로 다른 계약이다.
현재 control Compose의 추가 topdown bind는 계속 지원 밖이며 기존 PUBLIC/scenario-only 제약을 완화하지 않았다.
후속 실제 검증: unit 24 PASS/skip0, Python compile/whitespace PASS. 이번 head의 native 시험은 미실행이다.
구 source `f97dc0d2e51ca9590d9c41e9a3f27a5d20766c3e`와 후보 #1564
`9e4ac5172077eb5e2da57eddff1c9f02b73ba2e8`에서 API/engine Boot JAR offline 빌드는 성공했다.
Docker buildx가 읽기 전용 사용자 설정 경로에 쓰려다 실제 시험 image 생성 전에 실패했다.
사용자 전환 지시에 따라 이미지 빌드 재시도·새 장기 시험을 시작하지 않았다.

재개 순서: 최신 main을 다시 확인하고 현재 head의 unit/native 검사를 실행한다. 승인된 writable scratch에
Docker build 설정을 두어 구/후보의 정확한 앱 이미지를 준비하고, 합성 disposable PG16/Redis7에서
Spring/Flyway 부팅·원백업 rollback·동일 source 재개 시험을 구현·실행한다. 로컬 Boot JAR 성공을
운영 GHCR image admission이나 PRIVATE/fullbundle 증거로 사용하지 않는다. intake idle fence,
fullbundle bytes/exposure 보존, 인증 API/UI 및 운영 DDL lock/time/space는 여전히 남아 있다.
외부 독립 Claude 리뷰 연결은 부모 세션 담당이며 이 작업자는 merge/deploy/reset을 수행하지 않는다.

Python3, Docker, PostgreSQL16/Redis7이 필요하다. native 시험은 명시적 opt-in이며
synthetic fixture만 사용한다. 기본 skip 결과를 native 성공으로 보고하지 않는다.

```sh
python3 tools/ops/test_pep_migration.py
RUN_PEP_MIGRATION_DOCKER_TESTS=1 python3 tools/ops/test_pep_migration_docker.py
git diff --check
```
