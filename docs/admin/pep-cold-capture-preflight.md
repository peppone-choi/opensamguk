# PEP 냉간 캡처 준비 관문

현재 상태: **준비 전용**. 이 문서와 `tools/ops/pep_cold_capture_operator.py`는 운영 서비스를 정지·재시작·reset하지 않는다.

## 지금 확인할 것

운영 호스트에서 최신 검토된 소스와 실제 control stack 경로를 고정하고 다음을 실행한다. 비밀 env를 출력하지 않는다.

```bash
umask 077
python3 tools/ops/pep_cold_capture_operator.py prepare \
  --server pep --confirm 'PREPARE pep' \
  --stack-dir /home/peppone_choi/opensamguk-docker
```

이 명령은 같은 production flock을 잡은 상태에서 `maintenance-v1/drained`, marker 존재와 lifecycle journal 부재,
PEP 다섯 컨테이너의 실행·소유권·이미지 ID, PG/Redis 볼륨, API/engine 시나리오 읽기 전용 bind,
선택된 세계와 시나리오 코드, 이미지 pin 형식을 검증한다. 결과에는 비밀 env·월드 ID·계정 정보가 없다.
실패하면 어떤 컨테이너도 바꾸지 않는다. 성공도 **냉간 백업·격리 복원·앱 재적재·인증 읽기 성공을 뜻하지 않는다**.

## 실제 냉간 작업의 미완료 관문

전투·W4의 현재 main SHA 합격 전에는 PEP 엔진을 정지·재시작하지 않는다. 합격 후에도 다음 전체 작업을
하나의 검토된 Python 프로세스와 같은 `Recovery.locked()` 안에서 수행해야 한다.

1. 유지보수 controller가 `drained`이고 production lock을 독점했는지 다시 검사한다. 다른 lifecycle 작업과
   Gateway 서버 정의를 읽어 pending/repair-required가 없음을 확인한다.
2. 기존 API·web intake를 차단·정지한 뒤 engine을 정상 종료한다. `RELOAD_REQUIRED`는 성공한 flush의 증거가
   아니므로 마지막 committed DB 상태와 미완료 inbox를 별도로 기록한다.
3. 실행 중인 PG/Redis에서 비공개 row count·Flyway·정규화 논리 dump hash·AOF key count를 수집한다.
   `data/scenarios` 전 파일을 `preserve_scenario_tree`로 비공개 동반 bundle에 복사하고 원본·복사본의
   파일별 SHA-256, bundle manifest 연계를 대조한다. 불완전한 복사본은 `INCOMPLETE` 표지를 남긴다.
4. PG/Redis를 정상 종료한 뒤 동일 `Recovery` 인스턴스로 `capture`·`verify`를 호출한다. manifest/payload
   SHA-256, 원본 DB/Redis 수치, 정확한 이미지 ID와 env를 비교한다.
5. `PepApplicationDrill.prove`로 기존 이미지의 격리 engine 세계 재적재·READY/paused 상태를 증명한다.
   `PepAuthenticatedReadProbe`가 동일 격리 네트워크에 캡처된 game-api 이미지와 외부 시나리오 사본을
   붙이고, 기존 Gateway의 설정된 ADMIN 계정으로 합법 로그인한 뒤 토큰 검증 키 일치·보호 API의
   무인증 거부/인증 읽기·지도 읽기를 확인한다. 토큰과 응답 본문은 보고서에 저장하지 않는다.
   `require_complete_old_application_proof`는 login·identity·server entry·world·map 읽기 다섯 증거가
   동일 bundle/world에 묶이지 않으면 성공 판정을 거부한다. ADMIN에게 이 세계의 장수가 없으면
   `/api/my-page`의 404는 허용하지만 `/api/my-cities`의 인증 200과 지도 城 수 일치는 필수다.
6. 검증 실패는 성공으로 기록하거나 맹목적으로 재시작하지 않는다. 원본 다섯 컨테이너와 유지보수 창을
   그대로 보존하고 정확한 실패 단계를 보고한다. 전체 검증 성공 후에도 원본 재개·QA reset·새 이미지 승격은
   각각 그 단계의 W4/전투·세계 형식·시나리오·CI 게이트를 재확인한 뒤 수행한다.

`PepColdCaptureOperator.capture_and_prove`는 위 1–5단계의 저장소·격리 엔진·인증 읽기를 코드로
표현하지만 CLI에서 호출할 수 없고 운영 검증·독립 리뷰를 통과하지 않았다. W4/전투 PASS 후보를
인자로 요구해도 그 값의 출처와 실제 이미지 SHA를 아직 독립적으로 인증하지 못한다. 성공 반환도
`ready_for_reset=false`다. 원격 운영 실행에 사용하지 않는다.
다섯 컨테이너 정지 뒤 예외가 나면 `status.json`에 실패 단계가 남고 원본을 자동 재시작하지 않는다.
따라서 원본 재개/QA reset과 최종 후보 pin을 포함한 검토 완료 전체 harness는 아직 없다.

`scenario_990002`와 1447 城은 새 QA 목표다. 현재 PEP의 원본
`scenario_1020`·이미지·DB는 냉간 bundle과 격리 복원에서 그대로 보존한다.
