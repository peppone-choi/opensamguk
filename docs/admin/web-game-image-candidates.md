# web-game 이미지 후보 발급

이 경로는 승인 준비에 필요한 web-game 이미지만 GHCR에 발급한다. 운영 반영은 별도 대상 승인과 제어 저장소의 단독 서비스 절차를 따른다. 기존 Build + Deploy workflow의 재실행으로 대체하지 않는다.

## 발급 계약

- `.github/workflows/build-web-game-image.yml`은 수동 실행만 가능하며 GitHub-hosted `ubuntu-24.04`에서 동작한다. 준비 PR의 push/CI는 발급을 실행하지 않는다.
- 소스 `T`는 실행 직전에 명시한 현재 main의 lowercase full40이다. 발급 코드 `I`와 검토된 아홉 입력의 지문 `P`도 명시한다. 다른 브랜치의 full40 또는 아홉 입력 바이트가 바뀐 main은 거절한다.
- 발급 workflow와 도구는 별도 `github.workflow_sha`에서 받는다. `issuer_sha`는 발급 코드, `source_sha`는 이미지 코드다. 둘이 같은 SHA일 필요는 없다.
- 필수 입력 `expected_issuer_sha`, `expected_source_sha`, `expected_source_inputs_sha256`에는 기본값이 없다. contents:read admission job은 실제 `github.workflow_sha=I`를 대조하고 고정 GitHub API에서 I의 발급 도구를 읽는다. 도구 파일의 path/type/base64/size/Git blob SHA를 확인한 후 같은 `admit_source` 함수를 실행한다. checkout/login/build는 이 job 성공 뒤 실행하며 image job은 admission이 낸 I/T/P/도구 SHA256만 소비한다.
- Dockerfile은 `docker/web-game.Dockerfile`, SHA256 `e122a4e0f079a97ba470c088a8569e29a7ab9a2c67bb941200b5fb17245103bd`다. 깨끗한 추적 파일만 가진 소스 checkout에서 빌드하며 비템플릿 `.env*`, ignored/untracked 파일, symlink는 거절한다. 실제 환경 파일 내용은 읽거나 출력하지 않는다.
- 공개 빌드 인자는 `ASSET_PREFIX=/game`, `GATEWAY_WEB_URL=http://web-gateway:3000`, `NEXT_PUBLIC_GATEWAY_URL=`로 고정한다. 기존 정상 빌드의 `/game`과 Dockerfile 기본 gateway 값을 명시한 것이다. 운영 컨테이너의 실제 환경값 확인을 대신하지 않는다.
- 이 소스는 `NEXT_PUBLIC_TOPDOWN_SCREENS`로 제품 topdown 화면을 선택할 수 있으며 이 발급에서는 해당 스위치를 전달하지 않는다. 전용 battle flag reader는 없으며 `NEXT_PUBLIC_MAP_RENDERER`도 전달하지 않아 production 빌드의 lab 진입은 꺼진다. runtime `NODE_ENV=production`과 관련 public flag의 부재/빈값을 이미지 config에서 확인한다. lab 404 및 제품 동작은 실제 이미지 발급 후 별도 검증한다.
- 플랫폼은 `linux/amd64`, 저장소는 `ghcr.io/peppone-choi/opensamguk`다. 대상 운영 플랫폼 적합성은 C8 제어 검토에서 확인한다. 태그는 `web-game-candidate-<source40>-<run_id>-<attempt>`다. 공유 latest·기존 서비스 태그를 갱신하지 않는다.

## 소스 계약 버전 갱신

현재 계약은 `web-game-source/v3`이다. 과거 cf7 전용판과 ed5470 전용 v2의 독립 리뷰는 v3 변경분의 리뷰 근거가 아니다. 실행 때 현재 main을 조회하되 빌드 입력의 허용판은 아래 아홉 SHA256으로 고정한다. 입력 지문 P는 이 표의 정렬된 compact JSON SHA256인 `5a51ed78074986ba099a223f704e6d78546d5fcb4a39f07b4f8006f310fa0272`다. 호출자가 다른 P를 입력해 새 바이트를 허용할 수 없다.

읽기 admission은 main ref의 commit SHA=T, T의 commit/tree, 아홉 regular blob의 경로·Git blob SHA·contents 응답·실제 SHA256을 연결한다. tree 누락/중복/truncated/symlink, 응답 오류/redirect/timeout/권한 실패는 거절한다. 아홉 파일 확인 뒤 main=T를 다시 확인한다. 이미지 job은 I checkout의 도구 바이트와 admission proof를 대조하고 `admission.json`을 기록한다. T checkout의 깨끗한 추적 파일·아홉 바이트와 main 일치를 빌드 전에 다시 검증한다.

| 입력 | SHA256 |
| --- | --- |
| `docker/web-game.Dockerfile` | `e122a4e0f079a97ba470c088a8569e29a7ab9a2c67bb941200b5fb17245103bd` |
| `.dockerignore` | `fa8571682ab2e7f42468d715a05212f2133e7408f3ae92860a41cb04f9115528` |
| `web/package.json` | `4a721a6e4e3b0c959d6a097f7634aa9bb23ac5502b258764fe3ba74773bfa91c` |
| `web/pnpm-lock.yaml` | `00e7568e6acebe296919df4a3d5b81ba6af0a33b567cbe8f40837d29c3889bfb` |
| `web/pnpm-workspace.yaml` | `f40581c897f2e74ca856fb8753588d45a829b204eb62c91cd69bcc95ddb993b1` |
| `web/game/package.json` | `d5330e4a9b79ec653c7488470fcdeb9892b65ce4ace6d6838bcaeb5f46c58d00` |
| `web/gateway/package.json` | `145d6a1b6f45de323c22d3bd754211128949880f143f5cfcaa199d5a754ea4ad` |
| `web/shared/package.json` | `e536e4ca90c881ff02de095aa243b50acf9d170abafe093ace7a3c8210c5ae44` |
| `web/game/next.config.mjs` | `c1656a6b55d094dcbe24771421d887cdf34443ac0edc3fc9f56c726e303149c2` |

plan/candidate의 `source_contract_version=3`, `source_inputs_sha256=P`, `source_contract_sha256`는 T·P·아홉 핀·repo·platform·buildargs·flags의 계약을 기록한다. 기존 `web-game-image-candidate/v1` artifact 형식은 유지한다. 오프라인 테스트는 독립적으로 고정한 아홉 입력 바이트를 사용하며 네트워크나 현재 checkout에서 기대값을 만들지 않는다.

발급 전 C0가 최종 T/I/P와 독립 리뷰·현재 CI 근거를 연결한다. 준비 이후 main이 움직이면 실행을 멈추고 T를 다시 고정한다. 아홉 입력이 같을 때 새 T를 선택할 수 있으며 입력 바이트가 바뀌면 새 허용판의 코드·fixture·문서와 독립 리뷰가 필요하다. OCI/provenance 검증이 끝난 뒤에도 main=T를 확인한 후에만 `candidate.json`을 만든다. 이 마지막 확인 전에 태그가 push될 수 있다. 검증 실패 태그는 성공 후보가 아니며 자동 삭제·재시도하지 않는다.

## 승인과 결과 읽기

실행 시점 main의 pep 웹 승격과 필요한 후보 발급에는 사전 승인 기록이 있다. 이 v3 변경분에는 새로운 exact issuer head의 정상 CI와 독립 리뷰가 필요하다. C0가 최종 T/I/P와 실행 근거를 인계한 뒤 승인 범위의 발급을 수행한다. 운영 적용은 C8의 웹 단독 절차·복원 선행을 따른다. API·engine·DB·maintenance 조작은 이 승인에 포함되지 않는다.

기존 `deploy.yml`은 main 병합에 자동 반응한다. D19 방침에 따라 정상 PR CI·독립 리뷰·머지 열차를 따른다. 과거 A01 workflow 정지·복원 등록안은 취소됐으며 이 발급기를 위해 배포 workflow를 정지하지 않는다.

수동 workflow는 default branch 등록 후 실행한다. 최종 실행 ref가 검토된 I를 가리키는지 확인하고 세 필수 입력을 명시한다. branch/tag는 움직일 수 있으므로 실제 `github.workflow_sha` 대조를 통과해야 한다. guard는 ref 입력 실수를 검출하며 운영 승인 권한을 부여하지 않는다.

expected_issuer_sha 비교는 승인 권한을 증명하지 않는다. ref와 기대값을 같은 dispatch 권한자가 선택하며 guard 코드도 실행 파일 안에 있다. 검토받지 않은 SHA를 기대값으로 함께 입력하거나 guard가 없는 사본을 실행하는 경우를 차단하지 못한다. 실제 실행 권한은 저장소 dispatch 권한으로 관리하고, C0/K0의 대상 승인·독립 리뷰는 별도 운영 절차로 확인한다. 소스 T에는 현재 main 일치가 필수다. 발급 코드 I의 대상 승인과 독립 리뷰는 별도 운영 기록에서 확인한다.

성공 artifact의 `candidate.json`과 Actions summary에서 다음을 확인한다.

| 필드 | 의미 |
| --- | --- |
| `source_sha`, `issuer_sha` | 실행 시점 main T와 실제 발급 workflow I의 전체 Git SHA |
| `source_inputs_sha256`, `admission.json` | 검토된 아홉 입력 지문 P와 tree/blob/도구 바이트 admission 증거 |
| `source_contract_version`, `source_contract_sha256` | T와 아홉 입력 계약의 버전과 지문 |
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

C8 실행 핀은 source40@linux/amd64 platform manifest digest다. index는 provenance 보존용, config digest는 실행 manifest가 아니다. 후보 tag와 C8의 web-game-source40@digest가 실제 pull 및 Config.Image에서 호환되는지는 실발급 후 별도 승인된 GitHub-hosted probe로 확인한다. 현재 source/합성 검증이나 Compose config만으로 실 pull 성공을 판정하지 않는다.
