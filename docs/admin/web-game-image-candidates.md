# web-game 이미지 후보 발급

이 경로는 승인 준비에 필요한 web-game 이미지만 GHCR에 발급한다. 운영 반영은 별도 대상 승인과 제어 저장소의 단독 서비스 절차를 따른다. 기존 Build + Deploy workflow의 재실행으로 대체하지 않는다.

## 발급 계약

- `.github/workflows/build-web-game-image.yml`은 수동 실행만 가능하며 GitHub-hosted `ubuntu-24.04`에서 동작한다. 준비 PR의 push/CI는 발급을 실행하지 않는다.
- 현재 승인 검토용 빌드 소스는 `cf7a1968993e41a98940031c60683129e9ae919a` 하나다. 다른 SHA는 전체 40자리여도 거절한다. 소스 후보를 바꾸려면 발급 도구의 소스·Dockerfile 핀과 검토 근거를 먼저 갱신한다.
- 발급 workflow와 도구는 별도 `github.workflow_sha`에서 받는다. `issuer_sha`는 발급 코드, `source_sha`는 이미지 코드다. 둘이 같은 SHA일 필요는 없다.
- Dockerfile은 `docker/web-game.Dockerfile`, SHA256 `e122a4e0f079a97ba470c088a8569e29a7ab9a2c67bb941200b5fb17245103bd`다. 깨끗한 추적 파일만 가진 소스 checkout에서 빌드하며 비템플릿 `.env*`, ignored/untracked 파일, symlink는 거절한다. 실제 환경 파일 내용은 읽거나 출력하지 않는다.
- 공개 빌드 인자는 `ASSET_PREFIX=/game`, `GATEWAY_WEB_URL=http://web-gateway:3000`, `NEXT_PUBLIC_GATEWAY_URL=`로 고정한다. 기존 정상 빌드의 `/game`과 Dockerfile 기본 gateway 값을 명시한 것이다. 운영 컨테이너의 실제 환경값 확인을 대신하지 않는다.
- 이 소스에는 `NEXT_PUBLIC_TOPDOWN_SCREENS`와 전용 battle flag reader가 없다. `NEXT_PUBLIC_MAP_RENDERER`를 전달하지 않으며 production 빌드의 lab 진입은 꺼진다. runtime `NODE_ENV=production`과 관련 public flag의 부재/빈값을 이미지 config에서 확인한다. lab 404 및 제품 동작은 실제 이미지 발급 후 별도 검증한다.
- 플랫폼은 `linux/amd64`, 저장소는 `ghcr.io/peppone-choi/opensamguk`다. 대상 운영 플랫폼 적합성은 C8 제어 검토에서 확인한다. 태그는 `web-game-candidate-<source40>-<run_id>-<attempt>`다. 공유 latest·기존 서비스 태그를 갱신하지 않는다.

## 승인과 결과 읽기

현재 단계에서는 workflow를 dispatch하거나 helper의 `issue`를 실행하지 않는다. C0가 검토 완료된 발급 workflow SHA와 source40에 대한 **이미지 발급 대상 승인**을 받은 뒤 다음 실행을 배정한다. 운영 승인과 발급 승인은 별도다. Actions 실행 시 승인된 workflow ref를 선택하고 source_sha 입력을 확인한다.

기존 `deploy.yml`은 이 workflow/tools 변경의 main 병합에도 자동 반응한다. 준비 PR은 draft로 유지하며, 정상 PR CI와 독립 검토 이후 C0가 자동 운영 경로에 대한 대상 승인을 확인하기 전 ready/merge하지 않는다. 이 준비로 기존 배포 trigger나 운영 제어를 변경하지 않는다.

성공 artifact의 `candidate.json`과 Actions summary에서 다음을 확인한다.

| 필드 | 의미 |
| --- | --- |
| `source_sha`, `issuer_sha` | 이미지 소스와 발급 도구의 전체 Git SHA |
| `source_pins_sha256`, `build_args`, `flags` | Dockerfile·lockfile·설정 입력 핀과 빌드 계약 |
| `index_reference` | provenance attestation을 포함하는 OCI index의 불변 참조 |
| `platform_reference` | linux/amd64 실행 이미지 manifest의 불변 참조 |
| `config_digest` | 실행 이미지 config digest; manifest/index와 별개 |
| `attestation_manifest_digest`, `provenance` | 플랫폼에 연결된 attestation 및 max SLSA v0.2 내용 |
| `deployment_approved: false` | 발급 검증 통과만으로 승격 권한이 생기지 않음 |

Buildx는 max provenance와 원시 빌드 메타데이터를 남긴다. 발급 도구는 registry 불변 참조의 platform/config/OCI source labels와 provenance의 revision·repository·Dockerfile 바이트·buildargs·platform을 대조한다. 필드가 없거나 달라지면 성공 후보 파일을 만들지 않는다. 태그가 push된 뒤 검증에 실패할 수도 있으므로 성공 artifact가 없는 태그를 승인 후보로 사용하지 않는다. 자동 삭제나 재시도는 하지 않는다.

로컬 Dockerfile mount는 [고정 Buildx 입력 구현](https://github.com/docker/buildx/blob/v0.37.2/build/opt.go)에 따라 filename이 `web-game.Dockerfile`로 기록된다. [Git metadata 구현](https://github.com/docker/buildx/blob/v0.37.2/build/git.go)의 `vcs.localdir:dockerfile=docker`, `vcs.localdir:context=.`와 함께 검사해 저장소 상대 경로 `docker/web-game.Dockerfile`을 연결한다. OCI media type은 exporter 옵션으로 명시한다.

Buildx v0.37.2와 action 전체 SHA를 고정한다. Dockerfile의 base tag는 기존 파일 그대로다. max provenance의 실제 base material digest와 발급 run은 남지만 base 이미지/네트워크까지 재현 가능하다는 보장은 아니다. 결과 index는 이 workflow에서 서명한 공급망 증명이 아니며 승인 검토 자료다.

관련 형식 정본: [Docker SLSA provenance](https://docs.docker.com/build/metadata/attestations/slsa-provenance/), [Buildx metadata](https://docs.docker.com/reference/cli/docker/buildx/build/), [imagetools inspect](https://docs.docker.com/reference/cli/docker/buildx/imagetools/inspect/). 단일 image의 format Manifest는 config를 포함하지 않는 descriptor이므로 config 연결은 [고정 Buildx 구현](https://github.com/docker/buildx/blob/v0.37.2/util/imagetools/printers.go)에 맞춰 raw manifest의 해시와 config digest로 검사한다.

## 저비용 준비 검증

저장소 루트에서 `python3 -m unittest discover -s tools/ci -p 'test_web_game_image.py' -v`로 fake command 양성·음성 검증을 수행한다. 실제 Docker build, registry publish, 운영 서비스 제어는 이 테스트에 포함되지 않는다. 정상 CI의 tools/ci unittest discovery가 같은 테스트를 실행한다.

발급 이후 C8가 registry/platform·복원 pull·서비스 단독 제어를 검토하고, K10이 실제 lab 차단과 제품 화면을 확인한다. #1070/#1101을 포함한 웹 단독 pep 승격 및 동작 증거가 먼저이며 PNG API는 후속 별도 대상 승인이다. API·engine·scenario·DB·공유 배포·운영 알림은 이 발급 경로의 실행 대상에 없다.
