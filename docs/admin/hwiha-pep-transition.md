# pep 휘하 S3 전환 준비

이 문서는 豫州 한 州 실스택 완주 뒤 사용할 전환 런북이다. 운영 배포, 월드 초기화, 워크플로 실행은 이 문서의 작성으로 수행되지 않는다. 실제 전환은 대상과 시점을 명시한 별도 승인 후 진행한다.

## 후보와 고정할 값

| 항목 | 후보 값 | 실행 직전 확인 |
|---|---|---|
| 서버 | `pep` (`spep` 내부 스택) | `GET /admin/env/servers/pep`의 서버 ID와 현재 시나리오 |
| 새 시나리오 | `scenario_990002` — 휘하 豫州 조각, 합성 QA 데이터 | 운영 카탈로그와 이미지 내부 시나리오 파일의 제목·해시 일치 |
| 지도 판 | `han-world-v3` 1168 城 | #865가 먼저 병합되면 1224 城으로 다시 고정하고 시나리오 생성 테스트·창고 전 縣 검사를 재실행 |
| 승격 SHA | W0–W4 통과 후 `main`의 정확한 커밋 SHA를 기록 | game-api·game-engine·web-game·gateway 이미지의 출처가 같은 SHA인지 확인 |
| 시계 | 현재 pep의 `turnTerm`을 읽은 뒤 `turn_term=current` | 관리 API 값과 서버 환경값 일치, 실제 틱 진행 확인 |
| 세대 | 현재 pep의 `generation`을 읽은 뒤 `generation=current` | 기존 세대와 전환 의도를 기록 |

`scenario_990002`의 병력·재고·장수 수치는 PROVISIONAL이다. W4 순별 사건표, 12–24순 포위 기준과 월단평 편향을 검토한 결과를 전환 기록에 붙인다.

## `reset-game-server.yml` 입력 초안

| 입력 | 값 |
|---|---|
| `server` | `pep` |
| `confirm` | `RESET pep` |
| `create_backup` | `true` |
| `scenario_code` | `scenario_990002` |
| `generation` | `current` (실측값과 의미를 확인) |
| `turn_term` | `current` (실측값과 의미를 확인) |
| `expected_city_count` | `1168` (#865 병합 시 `1224`로 재검증) |
| `expected_first_city` | `경조윤 장안현` (승격 지도 API로 재검증) |

워크플로 기본값 `scenario_1020`은 이 전환의 대상이 아니다. 실행 전 입력 화면의 여덟 값을 모두 직접 대조한다. `reset-game-server.yml`은 외부 시나리오 디렉터리를 재생성하거나 기존 파일을 유지할 수 있으므로, 이미지의 `scenario_990002`가 실제 시드에 사용될 경로까지 확인한다.

## 전환 직전 실측과 중단 조건

1. `GET /admin/env/servers/pep`와 `GET /api/admin/game-settings`에서 현재 시나리오·세대·턴텀·월드 상태를 기록한다. `GET /api/servers`와 `GET /api/server-basic-info/pep`의 공개 표기도 대조한다.
2. 운영 호스트의 디스크 여유, PostgreSQL·Redis 백업 가능 공간, 기존 백업 위치를 확인한다. 백업은 켠다.
3. `GET /admin/turn-daemon/status`의 `lastTickError`, `failedTicks`, `consecutiveFailures`, `lastTickCompletedAt`, `lastSuccessfulTickAgeSeconds`를 확인한다. `/actuator/health`의 UP만으로 턴 루프 정상으로 판정하지 않는다. 오류 내용에 월드 데이터가 있을 수 있으므로 공개 기록에는 예외 종류와 카운터만 남긴다.
4. W0 main CI 9개, W1 관문·적색 짝의 반복 결과, W2 단위·모의 결과, W3 화면 테스트, W4 로컬 스택의 화면 9종·API·DB 증거와 `tick failed=0`을 전환 기록에 연결한다. 하나라도 미통과면 전환을 중단한다.
5. 승격할 정확한 SHA와 이미지 digest, 시나리오 SHA, 지도 城 수·첫 城, 직전 운영 상태를 기록한다. #865 병합 여부가 바뀌면 입력값과 합성 시나리오를 다시 만든다.

별도 승인 후 승격과 월드 초기화를 한 작업으로 추적한다. 완료 뒤에는 새 월드의 시나리오·城 수·첫 城, 사람 가입·출사, 순 증가, `lastTickError`와 `failedTicks`, 화면과 API를 다시 검증한다. 실패하면 워크플로의 복구 상태와 백업을 기준으로 복구 절차를 결정한다.
