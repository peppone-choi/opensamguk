# PEP QA 전환 실행기 계약

`tools/ops/pep_qa_cutover.py`는 검토 중인 **프로그램 API**다. 현재 CLI 실행 경로가 없고 운영 reset을 호출하지 않았다. 실제 운영 적용은 W0–W4와 W1 세 반복의 최종 동일 main SHA 증거, Docker 유지보수 reset 접점, 사후 인증·순서 진행 probe가 각자 리뷰·병합된 뒤에만 가능하다.

## 한 번의 시도

`PepQACutover.run`은 `Recovery.locked()` 하나를 전 과정에 유지한다. 입력에는 #1026 run/attempt/collector SHA, 현재 main SHA, 지도·시나리오 SHA, 필수 artifact ID 3개와 W1 ID 4개, 새로운 32자 operation ID, 비공개 단일 사용 lease가 필요하다. lease는 보고서·상태 파일·응답에 기록하지 않는다.

1. GitHub의 현재 main과 동일 실행의 아카이브 digest를 확인하고 W0/W2 XML·강제 예외 0·W3·DB 기반 W4 전투·첫 승자 및 W1 세 반복을 다시 검사한다. 결과가 읽기 전용이라는 사실과 SHA 일치도 검사한다.
2. 후보 소스 HEAD, 지도 및 `scenario_990002.json` 파일 SHA, 운영 스택에 놓인 외부 시나리오 사본 SHA, 이미 가져온 여섯 이미지 태그의 Docker 이미지 ID를 QA 아티팩트와 대조한다. PEP 원본은 1세대여야 한다. 하나라도 다르면 구 서비스를 정지하지 않는다.
3. 기존 `PepColdCaptureOperator`가 서비스 다섯 개를 순서대로 정지하고 원본 DB/Redis, 냉간 bundle, 격리 복원, 구 앱 재적재와 인증 읽기를 확인한다. `ready_for_reset=false`는 이 단계의 정상적인 드라이런 표지이므로 handoff의 독립된 완료 필드를 검사한다.
4. 외부 GitHub 증거와 후보 핀을 다시 검사한다. 드리프트가 있으면 구 서비스가 정지된 상태로 중단한다. 자동 재시작하지 않는다.
5. Docker 배포기 PR #60의 loopback `POST /servers/reset`에 lease, `RESET pep`, `scenario_990002`, 2세대, 현재 main SHA의 `imageTag`·`webGameTag`, operation ID를 **한 번만** 제출한다. 배포기 작업이 `succeeded`로 끝날 때까지 상태를 확인한다. 요청이 수락된 뒤 통신이 끊겨도 같은 lease를 다시 제출하지 않는다. `reset-game-server.yml`과 `promote-game-server.yml`은 사용하지 않는다.
6. 별도 사후 probe가 새 컨테이너 건강, 인증된 세계·지도 읽기, 실제 순서 진행, 여섯 이미지 ID와 외부 시나리오 SHA를 확인해야 `observed` 성공을 기록한다. 실패나 불명확한 결과에서는 원본 엔진을 기동하거나 유지보수를 자동 해제하지 않는다.

`cutover-status.json`은 냉간 작업 디렉터리에 비공개로 기록되며 단계와 실패 위치만 남긴다. DB 본문·토큰·lease는 없다. 사후 probe의 실제 운영 구현과 최종 QA 아티팩트는 아직 준비되지 않았다. 따라서 이 PR은 draft로 유지하며 운영 전환 GO를 뜻하지 않는다.
