# 완전 탑다운 지도 artifact 생성

`map-artifact.yml`은 수동으로 **검증 후보 파일과 증거**를 만든다. 운영 `deploy.yml`과 의존/호출 관계가 없으며 이미지 build·push, VM 설치·마운트·TOPDOWN 환경·DB·API 재시작·제품 플래그 전환을 수행하지 않는다. 후보 성공은 공개/활성화 승인이나 운영 검증 성공이 아니다. 런타임 계약은 [topdown-map-bundles.md](topdown-map-bundles.md)를 따른다.

## 실행 전 배정

- 독립 리뷰를 마친 workflow/도구가 들어 있는 **같은 40자리 commit**을 workflow 실행 ref와 `source_sha`로 고정한다. 두 값/실제 checkout이 다르거나 checkout이 dirty이면 실패한다. 분기 이름을 source pin으로 받지 않는다.
- 전용 ephemeral Linux x64 runner에 `self-hosted, Linux, X64, map-artifact` label을 배정한다. 운영 runner/GCP VM에 이 label을 추가하지 않는다. 하나의 runner에는 한 작업만 배정하고 다른 저장소/작업과 자원을 공유하지 않는다. 운영 파일/마운트·자격증명·Docker socket 접근 권한을 부여하지 않는다.
- 계획 예산은 2CPU 이상·RAM16GiB·작업공간 여유10GiB다. 도구는 `/proc/meminfo`의 MemTotal15GiB 이상(표기16GiB 장비의 커널 사용분 허용), MemAvailable12GiB 이상·free disk10GiB를 요구한다. 측정된 제품 최소 RAM/ETA가 아니다. source/runner 배정 없이 dispatch하지 않는다.
- Python **3.12.10**, NumPy **2.4.6**, Pillow **12.2.0**을 사용한다. 의존성 파일은 기존 map contracts job과 같은 버전이다. Python/platform/libc/zlib build·runtime/설치 버전과 source 파일 SHA를 증거로 남긴다. zlib runtime도 bake identity의 입력이다. GitHub actions는 공식 저장소의 full commit으로 고정했다.
- workflow token은 contents:read만 사용하고 checkout credential을 보관하지 않는다. 운영 secrets나 환경 전체를 로그에 싣지 않는다. source_sha는 env로 전달한 뒤 hex와 workflow SHA 일치를 검사한다.
- job concurrency는 하나, 실행 중 작업을 새 dispatch로 취소하지 않는다. worker=1, OMP/OpenBLAS/MKL/NumExpr/VecLib thread=1, PYTHONHASHSEED=0이다. 60분 제한이며 현재 제품 실행 시간은 미측정이다.

## 생성과 판정

`build_topdown_artifact.py build`는 checkout 밖의 새 output 디렉터리만 받는다. 다음 단계는 순차 실행하며 각 단계의 exit0과 GNU time RSS/시간 증거가 있어야 다음으로 진행한다.

1. `build_map_design.py --export`: 커밋된 설계 JSON으로 schema2 export 생성. `--build`/`--write-derived`로 원천 재생성하지 않음.
2. `bake_topdown_map.py --workers 1 --bundle-root`: full bake와 기존 생성기의 full check/불변 포장. partial 옵션을 제공하지 않음.
3. `bake_topdown_map.py --check-published`: 실제 포장 파일과 전역 구조/표본 재굽기 검사. root 부재 exit77은 실패이며 성공/검증으로 처리하지 않음.
4. `build_topdown_artifact.py audit`: exact source·runtime/전체 export·repo·kit 입력을 재대조하고 64hex canonical identity·partial false/region null·전체 chunk coverage·allowlist 파일·전송/raw SHA·크기/gzip mtime/OS 바이트·정적 공개 필드를 검사.

검사도 전역 구조를 재계산하므로 대형 실행이다. 로컬 자원 보류 중 export/bake/package/published-check를 실행하지 않는다. 작은 단위 fixture·Python syntax·workflow 구조 검사는 제품 bake 증거가 아니다.

원천 핀은 tiles/world/roads/ju/placements/economy/DEM/frozen catalog, 설계 JSON 전부, 생성기/감사 도구·workflow·requirements, kit catalog/index/stats/export와 source merge commit이다. export 8층의 전송 및 raw SHA·manifest SHA를 연결한다. `bakeId = SHA256(canonical inputFingerprint/mapRelease/kitVersion/formatVersion)`이며 manifest 실제 SHA는 별도로 기록한다. 런타임 API world binding은 release와 tiles/world/roads 3핀만 비교하므로 이 생산 단계의 다른 입력 감사가 필요하다.

공개 후보는 고정 tile/province plane·행정 연결·도시 preset/표시 geometry·고정 household 입력·정적 결함과 지문이다. places/defects는 명시된 field 집합만 허용하며 알려진 scalar 자리에 private object를 넣는 경우도 거절한다. nation/fog/개인 위치/계정/부대/명령/live 값을 추가할 수 없다. 이 검사는 새 공개 필드의 자동 승인이 아니다. schema가 바뀌면 공개 범위와 verifier를 함께 리뷰한다. 관의 실제 게임 통제는 이 산출로 검증되지 않는다.

## artifact와 증거

성공 시 `map-artifact-candidate-<run>-<attempt>`에만 아래를 넣는다.

- `candidate/bundles/<64hex bakeId>/`: manifest와 허용된 L0/L2·places·defects. uniform chunk는 파일 없이 raw SHA로 검사한다.
- `evidence/run.json`: source/workflow SHA, runtime/자원, 단계별 시작/종료/exit/wall/RSS, VERIFIED_CANDIDATE 상태. `publicationApproved:false`, `operationalChanges:false`.
- `evidence/source-inputs.json`, `runtime.json`, `bundle-audit.json`: 원천/전체 입력 identity, 실제 manifest bytes/SHA 및 모든 전송/raw SHA·길이·공개 검사 결과.
- 단계별 `.log`와 `.metrics.json`: Linux GNU time의 maxRssKiB/elapsedSeconds/exitCode. metrics도 누락/비정상이면 후보 성공이 되지 않는다.

생성/export 산출이나 원본 world JSON을 public bundle에 넣지 않는다. 실패/중단은 `map-artifact-diagnostic-<run>-<attempt>`에 **evidence만** 보관하고 후보 파일을 업로드하지 않는다. 강제 runner 종료/job timeout으로 diagnostic 업로드도 못 할 수 있으므로 중단 run을 성공으로 간주하지 않는다. preflight 초기 실패는 evidence가 없을 수도 있다. 후보 보존14일·diagnostic7일이므로 승인용 불변 보관소로 옮길 시점과 exact 체크섬을 별도로 정한다.

## 다음 승인·운영 경계

artifact에서 선택 ID/manifest SHA/각 파일 SHA·런타임/CI run을 확인한 뒤 정확 API 이미지 source/digest와 active world의 frozen release/3핀을 대조한다. artifact source와 API source는 서로 별도 핀이며 commit 이름만 같다고 검증을 생략하지 않는다.

운영 설치·public root의 **모든 retained ID**·read-only mount·TOPDOWN 환경·단일 service API 재생성·직전 PNG 호환 복원 핀은 별도 대상 승인이 필요하다. 선택 ID를 비워도 mounted root의 static endpoint는 제공될 수 있고 이미 public immutable로 보낸 자료는 캐시에서 회수할 수 없다. source/field 감사와 실제 origin/gateway bytes·ETag/304·world binding·화면 검증을 구분한다.

M1은 #1070→초점 후속 PR 병합→두 변경 포함 실제 pep 웹/검증→별도 PNG API 승격 순서를 유지한다. M2 API 공개/설정과 `NEXT_PUBLIC_TOPDOWN_SCREENS`를 포함한 게임/로그인 웹 빌드 승격도 서로 별도 승인이다. 이 workflow dispatch만으로 그 운영 행위가 허용되지 않는다.
