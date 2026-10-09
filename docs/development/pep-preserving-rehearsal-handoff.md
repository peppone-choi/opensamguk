# PEP 보존형 리허설 개발 인계

상태: 제한된 CLI의 합성 검증 완료, 독립 리뷰 대기. **운영 배포 준비 완료가 아니다.**
`ready_for_deployment=false`, `candidate_applied_live=false`를 유지한다.
원 인계는 `0f1d5039aa2b0f7148385f9e7ecc001200629b8f`, 기존 PR 체크포인트는
`68e7fd6e2b15eb778bff83a6826347f3334bc226`이다. [PR #1566](https://github.com/peppone-choi/opensamguk/pull/1566)의
소유 경로는 `tools/ops/pep_migration.py`, `pep_migration_clone.py`, `test_pep_migration.py`,
`test_pep_migration_docker.py`, `docs/admin/pep-preserving-migration.md`와 이 문서다.
제품 코드, workflow, staging 시험은 변경하지 않는다.

`prepare`/`rehearse`만 제공하며 live apply/reset은 없다. 한 lock에서 cold capture,
전체 PG/Redis clone 복구, 구/후보 app drill, 원백업 rollback 검증과 동일 원본 객체 재개를 조율한다.
PRIVATE validation consumer가 정지되거나 PUBLIC와 혼재해도 첫 정지 전에 거절한다.
매 clone 생성 전 backup payload/manifest/env, 앱 단계 전후와 재개 직전 scenario 사본·원본 bytes를 대조한다.
원본 Redis의 16 DB 값/absolute expiry를 cold clone·원본 storage 재개와 연결한다.
원본 데이터나 fingerprint가 다르면 API/engine을 재개하지 않으며 journal을 유지한다.

독립 Claude 리뷰에서 기존 Redis pending이 앱 정지 이후에야 거절되어 서비스가 정지된 채
남는 결함을 확인했다. 실제 격리 Redis pending 1개와 자체 서비스 fixture로 재현했고,
2차 preflight 뒤 journal 생성·첫 정지 전에 Redis fingerprint 검사를 추가했다.
기존 pending은 stop 0회·journal/operation 없음으로 거절한다. 정지 후 재측정은 유지하며
사전 관측 이후 정지 도중 새 pending이 생기는 경우는 앱 정지·journal 유지의 fail-closed 경계다.
자동 재개나 pending 변경 정책은 추가하지 않는다.

실제 앱 검증에서 두 ops 계약 누락을 찾아 보완했다. 현행 engine clock의 `worldId`/`serverId`를
복원 DB의 world/선택된 `ng_games`와 엄격히 대조하며, 여러 서버의 최신 행을 추정하지 않는다.
Redis의 pending=0인 빈 runtime consumer는 AOF 복원 후 사라지고 앱 시작 시 다시 만들어지는 것을
합성 앱에서 재현했다. stream의 모든 메시지 bytes·ID, 생성/삭제 ID·누적 추가 수·first-entry ID,
group 이름·delivery cursor·entries-read·lag와 key/expiry는 보존 검증한다. pending은 항상 0이어야 하며,
빈 consumer의 이름·수·idle/inactive 시계만 제외한다. 다른 타입은 기존 exact DUMP 대조를 유지한다.
native 시험은 값·expiry·DB15 binary 값·group/cursor 변경과 pending을 거절하는 음성 대조를 포함한다.

## 시험 이미지와 경계

구 product source는 `f97dc0d2e51ca9590d9c41e9a3f27a5d20766c3e`, V78 후보 #1564는
`9e4ac5172077eb5e2da57eddff1c9f02b73ba2e8`이다. 실제 API/engine Boot JAR offline 빌드는
각 2m54s/2m17s에 성공했다. 양 source의 map 파일 차이는 없다.
기존 읽기 전용 홈의 buildx 실패는 Docker 공식 `DOCKER_CONFIG` 옵션을 사용해
승인된 `/workspace/scratch/pep-rehearsal-docker-config`에 설정을 분리하여 해결했다.
보호 경로·보안 설정 변경과 인증정보 복사는 없다.

| 역할 | 실제 로컬 image config ID |
| --- | --- |
| 구 API | `sha256:368b60fd019a566d64d363366f0322db6d17252937cb43cbb41bc11fecf432fa` |
| 구 engine | `sha256:63d7f80ecd5d5041197c81ee0f169083007599bf47a23d23fc89086d1ffe2fdf` |
| 후보 API | `sha256:a4700cdf15f763e79caa6f8fe31f9fa8b7a8abb72f8b7a7e08b03867c9366da8` |
| 후보 engine | `sha256:db09dc48531ce4834ec79abb8e4312ab94c396564b9b66a93e97f45d199887c9` |

캐시된 JDK21 base·실제 Boot JAR·저장소 map 파일을 사용한 로컬 runtime image다.
`docker build --network=none --pull=false`로 생성했고 정확한 ID와 linux/amd64·OCI revision을 대조한다.
운영 Dockerfile 전체 빌드나 게시된 GHCR manifest/CI admission과 같다는 주장은 하지 않는다.
synthetic scenario와 disposable 내부 network/볼륨만 사용하며 host port는 게시하지 않는다.
테스트용 공개키는 새로 생성하고 개인키·실행 로그·backup·Docker 설정은 커밋하지 않는다.

`ApplicationMigrationTests`는 새 합성 fixture를 구 engine으로 만든 뒤 seed를 끈 구 API/engine의
paused READY, full cold capture/verify, 같은 원백업의 구/후보/구 rollback 세 cold clone,
동일 원본 storage/API/engine ID 재개와 데이터 불변을 실제 `clone_stage`로 검증한다.
중앙 `rehearse`/`resume`의 운영 maintenance/CI admission은 실행하지 않는다.
web은 capture 계약용 정지 placeholder여서 web/Gateway/인증 API/UI 검증을 포함하지 않는다.

## 검증과 남은 관문

현재 단위 검사는 기존 24개에 clock identity와 Redis pending 사전/정지 후 경계 검사를 추가한 30 PASS/skip0이다.
관련 recovery 49, application drill 27, cold capture 18, pep-loop 73도 PASS/skip0이다.
pending 사전 검사 수정 후 실제 앱과 native Docker 복구 회귀의 통합 실행은 2 PASS/skip0(445.751s)이다.
실제 앱은 구/후보/rollback 세 단계와 동일 원본 storage/API/engine 재개·저장 데이터 불변을 모두 통과했다.
실제 disposable Redis pending 1개와 자체 placeholder 서비스의 controller 시험도 모든 서비스 실행 유지,
stop 0회·journal/operation 없음·capture/resume 없음으로 거절했다. 이 표적 시험의 admission/preflight와
PG/inbox는 모의 처리했으며 운영 admission 전체 검증으로 간주하지 않는다. fixture는 모두 정리했다.
정지 후 새 pending의 fail-closed 경계는 단위 회귀로 확인했다.
Python compile/whitespace 검사는 PASS다. 최신 main `9e8cf57efd9b11f336b4a56a8e91e7faaa1610ba`와
이 PR의 소유 파일을 합친 별도 scratch에서 이전 head의 단위 28와 관련 167 검사가 PASS였고,
pending 수정 후 변경된 controller/시험을 반영한 단위 30도 PASS다. 수정 후 작업 트리의 관련 167 검사도 PASS다.
인계 당시 unit20/native1 또는 기존 68e7 head CI 성공을 새 head 검증으로 재사용하지 않는다.

PUBLIC/PRIVATE는 consumer 배치, fullbundle은 topdown 지도 mount·bake·asset 계약이며
storage recovery bundle과 다르다. 현재 control Compose의 추가 topdown bind는 계속 지원 밖이다.
현재 canonical PUBLIC/scenario-only 제약을 풀거나 운영 구성을 도구에 맞게 고치지 않는다.

남은 작업은 실제 source/world/images의 GHCR/CI admission, PRIVATE/fullbundle 정책·mount·bake·bytes·노출 보존,
intake idle fence, 운영 백업과 인증 API/UI, 전체 원본 서비스 재개, DDL lock/time/space 및 최종 인수다.
합성 검증된 제한적 CLI 코드를 병합하는 판단과 **전체 운영 배포 리허설 완료**를 구분해야 한다.
수동 reset이나 기존 자동 reset guard 해제는 없다. 외부 독립 Claude 리뷰와 main 병합은 부모 세션 담당이다.

```sh
python3 tools/ops/test_pep_migration.py
RUN_PEP_MIGRATION_DOCKER_TESTS=1 python3 tools/ops/test_pep_migration_docker.py NativeMigrationTests -v
# 정확한 source/image ID 환경변수는 관리자 문서의 계약을 따른다.
RUN_PEP_MIGRATION_APP_TESTS=1 python3 tools/ops/test_pep_migration_docker.py ApplicationMigrationTests -v
git diff --check
```

V78 SQL 위치와 opt-in image 계약·자원 정리·미검증 한계는
[관리자 문서](../admin/pep-preserving-migration.md)를 따른다. 기본 skip을 실행 성공으로 세지 않는다.
