# PEP PRIVATE/fullbundle 보존 리허설 인계

상태: 독립 Claude 계획에 따른 제한적 구현과 단위 검증 완료, **실제 fullbundle 앱 검증 미통과**.
`ready_for_deployment=false`, `candidate_applied_live=false`, `authenticated_reads_verified=false`를 유지한다.
원 인계 `0f1d5039aa2b0f7148385f9e7ecc001200629b8f`와 기존 [PR #1566](https://github.com/peppone-choi/opensamguk/pull/1566)의
scenario-only 합성 앱 PASS를 이번 fullbundle 완료 근거로 재사용하지 않는다.

## 구현된 범위

PUBLIC api/web 또는 PRIVATE validation api/web 한 쌍만 실행 중이어야 한다. 혼재·부분·반대쪽 정지 잔여는
첫 정지 전에 거절한다. PG/Redis/engine은 canonical 공유 이름이다. PRIVATE published ports/aliases는 비어야 하고
web upstream은 validation API다. PUBLIC 원래 PortBindings·ExposedPorts·aliases·network IDs와
모든 원본 ID/설정을 보존한다. 두 번의 preflight에서 topology/catalog/selected bake/노출과 실제 API 읽기를 대조한다.
기존 pending Redis 사전 검사는 journal/첫 정지 전이며 post-stop fingerprint도 유지한다.

engine의 scenario bind, API의 scenario+fullbundle topdown bind, web mount 없음만 허용한다.
mount 순서 대신 Destination을 찾으며 duplicate/extra/volume/symlink-parent/foreign filesystem을 거절한다.
실효 scenario/map root/bake/server/world 계약과 storage/image/Flyway/memory/lock guard를 유지한다.
`pep_topdown_catalog.py`는 선택되지 않은 bake까지 전체 tree를 검증한다. 앱 reader schema/format 1,
네 identity key의 재귀 ASCII key 정렬 compact JSON hash, no floats, full/region=null, 2MiB manifest,
16MiB raw, L0 262144 bytes, L2 4*cols*rows, transport/raw hash와 exact inventory를 대조한다.
현재 builder pin audit나 rebake는 수행하지 않는다.

기존 v1 recovery는 그대로이며 preserving capture만 topology/topdown을 포함한 v2다.
`<bundle>.topdown/{manifest.json,tree/}`의 전체 사본은 bundle manifest hash에 묶이고
INCOMPLETE·0700/0600·source/copy equality를 사용한다. 매 clone 단계 전후와 재개 전후에 원본·사본·원본 ID/노출을 검사한다.
API clone은 사본 topdown을 readonly mount하며 preview selected bake와 모든 bake manifest/assets의 HTTP bytes를 대조한다.
원본 API 재개에서도 같은 probe를 통과해야 web 재개로 진행한다. pre-stop 실패는 stop0/nojournal,
post-stop 실패는 journal 유지/자동 재개 없음이다. 어느 검증 실패도 ready 플래그를 true로 만들지 않는다.

`pep_authenticated_read.py`와 application drill은 Destination 기반 mount lookup과 v2 companion을 이해한다.
인증 QA를 이번 합성 증거로 선언하지 않는다. CI에는 migration/catalog 두 unit command만 추가한다.
control repo·pep_loop·tools/map·workflow staging 동작과 staging 시험은 변경하지 않는다.

## 이번 실행 증거

기준 main은 `4c4f64f3523e2e60a3ff6902a4f8b7c5f3dc5cbb`; 이후 최신 main
`8f39216bba62ce02ac27059b465a56ccccadfda1`의 상사 옵션 변경은 소유 ops/CI/docs 경로와 겹치지 않는다. 최신 main 전체 archive에 이번 변경을 적용한 별도 scratch에서도 관련 단위 223 PASS/skip0이다.
Python compile과 whitespace 검사도 PASS다. 이 검사는 최신 main runtime image/admission 증거가 아니다.

| 검사 | 이번 결과 |
| --- | --- |
| migration | 40 PASS / skip0 |
| topdown catalog + v1/v2 bundle | 10 PASS / skip0 |
| recovery | 49 PASS / skip0 |
| application drill | 27 PASS / skip0 |
| authenticated read mount regression | 6 PASS / skip0 |
| cold capture | 18 PASS / skip0 |
| pep-loop 기존 회귀 | 73 PASS / skip0 |
| 실제 PG16/Redis7 native v1 복구 | 기존 74fe 실행의 1 PASS / skip0; 이번 delta는 재실행하지 않음 |
| 실제 PUBLIC scenario-only v2 앱 | 1 PASS / skip0 (336.875s), capture→구/후보/rollback→원본 storage/API/engine 재개 |
| 실제 PRIVATE/fullbundle 별도 진단 | 1 ERROR / skip0 (48.693s): 최초 preview 503 SERVER_ADMISSION_UNAVAILABLE, capture/clone 미실행 |

단위 총 223 PASS이다. 음성 대조는 mixed/partial/stopped/exposure/mount 오류, 선택되지 않은 bake 손상,
identity/manifest/size/gzip/L0/L2/hash/link/FIFO/inventory, companion linkage와 모든 clone 경계 catalog drift를 포함한다.
두 합성 bake는 서로 다른 kitVersion이다. B5 bundled-source 오류의 운영 재현은 추론이며 수행하지 않았다.
기본 opt-in 없는 Docker skip은 성공으로 세지 않는다. 실제 이미지 시험의 native v1 PASS, PUBLIC scenario-only v2 앱 회귀, PRIVATE/fullbundle ERROR/NOT_RUN을 분리한다.

## 독립 리뷰 M1–M3 반영

리뷰는 `74fe3c6caa6e902b99e792c85394b27b1202692b`를 대상으로 부모가 제공한 독립 Claude 세션
`d4b419d2-74b5-44d3-96ad-eac7f9cbdcbe`에서 수행됐다. PRIVATE scenario-only가 실행 경로에 진입하는
누락을 수정해 candidate admission/stop/capture/resume 0회와 journal/operation directory 미생성을 확인했다.
기존 실행 가능한 PUBLIC scenario-only를 별도 실제 앱 시험으로 복원하고 v2/PUBLIC/topdown=null을
명시적으로 확인했다. fullbundle 진단은 별도 opt-in class라 PUBLIC 회귀를 대체하지 않는다.
삭제된 prepare/rehearse 명령, confirm/images/umask077, 전체 CI+필수6와 immutable GHCR 계약,
PASS boolean 금지 및 maintenance/journal/cursor/image/Redis pending 체크리스트를 관리자 문서에 복원했다.
이 delta의 독립 판정은 부모의 후속 현재-head 리뷰를 기다리며 자기 머지 가능 판정을 하지 않는다.

실제 synthetic Gateway 후속은 별도 `codex/synthetic-gateway-e2e-20261009` worktree의 WIP로 보존했으며
이 PR에는 포함하지 않는다. 그 별도 시험의 1 PASS를 이 PR의 인증/운영 admission 증거로 이전하지 않는다.

## 실제 이미지 차단 요인

구 product source는 `f97dc0d2e51ca9590d9c41e9a3f27a5d20766c3e`, V78 후보는
`9e4ac5172077eb5e2da57eddff1c9f02b73ba2e8`이다. 캐시된 실제 Boot JAR runtime image를 그대로 사용했다.

| 역할 | 로컬 image config ID |
| --- | --- |
| 구 API | `sha256:368b60fd019a566d64d363366f0322db6d17252937cb43cbb41bc11fecf432fa` |
| 구 engine | `sha256:63d7f80ecd5d5041197c81ee0f169083007599bf47a23d23fc89086d1ffe2fdf` |
| 후보 API | `sha256:a4700cdf15f763e79caa6f8fe31f9fa8b7a8abb72f8b7a7e08b03867c9366da8` |
| 후보 engine | `sha256:db09dc48531ce4834ec79abb8e4312ab94c396564b9b66a93e97f45d199887c9` |

신선한 합성 fixture만 구 engine으로 seed하고 이후 seed를 끈 API/engine의 health/paused READY를 확인했다.
별도 PRIVATE/fullbundle 진단을 이번 delta에서 재실행했고 최초 preview가 `SERVER_ADMISSION_UNAVAILABLE`(503)였다.
fixture Gateway는 의도적으로 연결되지 않았고 clone network도 외부 원천을 연결하지 않는다.
publication 원천을 clone에 안전하게 제공할 계약이 필요하다.
이 계약이 구현되기 전 fullbundle `rehearse`는 first stop/journal/candidate admission 전에 거절한다.
PRIVATE scenario-only도 같은 mutation 전 경계에서 거절하며 실행 가능한 리허설은 PUBLIC scenario-only뿐이다.
`prepare`의 readonly 관측과 v2 capture/companion 검증 코드는 유지하지만 fullbundle 실행 완료를 주장하지 않는다. filter를 건너뛰거나 Gateway mock을 운영 admission으로
선언하거나 네트워크 격리를 풀지 않았다. 따라서 이 PRIVATE/fullbundle 진단의 v2 actual capture/restore/rollback/resume 증거는 없다.
web은 계약용 placeholder여서 web/Gateway/인증 API/UI 증거도 없다. 모든 실행 소유 fixture는 정리했다.
이전 scenario-only e2 head의 실제 앱/native 2 PASS(445.751s)는 별개의 과거 증거다.

이 image들은 cached JDK21 base·Boot JAR·repo map 파일의 로컬 wrapper이며 production Dockerfile 전체/GHCR
manifest·현재 main 앱 image·CI admission 증거가 아니다. map 런타임 전체 파일 계약도 첫 probe 통과 이후 검증해야 한다.
공식 DOCKER_CONFIG scratch 이외 보안·보호 경로·인증 설정은 바꾸지 않았다.
임시 진단은 이 합성 API의 오류 코드만 분류했으며 키·env dump·실행 기록·backup·Docker 설정은 커밋하지 않는다.
Selection API의 검증된 연결은 없으므로 기존 예산/추천을 적용했다고 주장하지 않는다.

## 남은 인수

외부 독립 Claude head 리뷰는 부모 세션이 수행한다. 기존 비공개 backup의 `server.env`/Compose payload는
v1 복구 계약을 계승한다. 계획의 env 기록 금지가 추가 로그/metadata만인지 이 기존 payload까지인지는
별도 확인이 필요하다. 새 metadata에 effective env/full inspect를 기록하지 않는다.

실제 source/world/GHCR image admission·candidate issuer 인증·격리 clone publication 원천·원본/clone map HTTP bytes,
intake idle fence·운영 backup·인증 API/UI·전체 원본 서비스 재개·DDL lock/time/space 인수가 남았다.
부모 CI 32/32 보고를 local 서명 admission으로 만들지 않았다. 로컬 main CI 관측 자격도 없다.
live apply/reset/고객 DB 접근/merge/배포는 수행하지 않는다. 전용 branch의 draft PR로 인계한다.

```sh
python3 tools/ops/test_pep_migration.py
python3 tools/ops/test_pep_topdown_catalog.py
RUN_PEP_MIGRATION_DOCKER_TESTS=1 python3 tools/ops/test_pep_migration_docker.py NativeMigrationTests -v
# 관리자 문서의 여섯 immutable source/image 값을 설정해야 한다.
RUN_PEP_MIGRATION_APP_TESTS=1 python3 tools/ops/test_pep_migration_docker.py ApplicationMigrationTests -v
# 별도 PRIVATE/fullbundle 진단: 실행하지 않으면 NOT_RUN, 기존 관측은 최초 preview 503 ERROR.
RUN_PEP_MIGRATION_APP_TESTS=1 RUN_PEP_MIGRATION_FULLBUNDLE_TESTS=1 python3 tools/ops/test_pep_migration_docker.py PrivateFullbundleApplicationMigrationTests -v
git diff --check
```

세부 backup/opt-in 계약은 [관리자 문서](../admin/pep-preserving-migration.md)를 따른다.
