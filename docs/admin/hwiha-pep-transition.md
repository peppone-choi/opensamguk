# pep 휘하 S3 전환 준비

이 문서는 豫州 한 州 실스택 완주 뒤 사용할 전환 런북이다. 운영 배포, 월드 초기화, 워크플로 실행은 이 문서의 작성으로 수행되지 않는다. **2026-09-25 ADR-LITE-066에 따라 C단계는 저장·통신 식별자 개명과 삼모 은퇴가 끝날 때까지 중단한다.** 이 문서의 옛 경로·키·시나리오 파일명·입력값은 개명 뒤 다시 대조해야 하며, 현행 값으로 전환을 실행하지 않는다. 운영 서버와 운영 데이터 변경은 대상별 사용자 승인을 받는다. 아래 중단 조건은 그대로 적용한다.

## 후보와 고정할 값

| 항목 | 후보 값 | 실행 직전 확인 |
|---|---|---|
| 서버 | `pep` (`spep` 내부 스택) | `GET /admin/env/servers/pep`의 서버 ID와 현재 시나리오 |
| 새 시나리오 | `scenario_990002` — 휘하 豫州 조각, 합성 QA 데이터 | 운영 카탈로그와 이미지 내부 시나리오 파일의 제목·해시 일치 |
| 지도 판 | `han-world-v3` 1224 城 (#865 병합) | 시나리오 생성 테스트·창고 전 縣 검사와 API 城 수 대조 |
| 승격 SHA | W0–W4 통과 후 `main`의 정확한 커밋 SHA를 기록 | game-api·game-engine·web-game·gateway 이미지의 출처가 같은 SHA인지 확인 |
| 시계 | 현재 pep의 `turnTerm`을 읽은 뒤 `turn_term=current` | 관리 API 값과 서버 환경값 일치, 실제 틱 진행 확인 |
| 세대 | 현재 pep의 `generation`을 읽은 뒤 `generation=current` | 기존 세대와 전환 의도를 기록 |

`scenario_990002`의 병력·재고·장수 수치는 PROVISIONAL이다. W4 순별 사건표, 12–24순 포위 기준과 월단평 편향을 검토한 결과를 전환 기록에 붙인다.
전체 지도 역사 시나리오는 S4([#596](https://github.com/peppone-choi/opensamguk/issues/596)) 범위이며 이 컷오버의 기본 시나리오가 아니다.
합성 시나리오의 30개 적대 관계는 게임 규칙의 최대치인 13개월로 시작한다. 첫 월 정산 뒤에도 `diplomacy.state_code=0`과 남은 기간이 유지되는지 확인한다. 기간 0개월이면 첫 정산에서 중립으로 만료되어 조우 검증이 성립하지 않는다.

## `reset-game-server.yml` 명시 입력

| 입력 | 값 |
|---|---|
| `server` | `pep` |
| `confirm` | `RESET pep` |
| `create_backup` | `true` |
| `scenario_code` | `scenario_990002` |
| `generation` | `current` (실측값과 의미를 확인) |
| `turn_term` | `current` (실측값과 의미를 확인) |
| `expected_city_count` | `1224` |
| `expected_first_city` | `경조윤 장안현` (승격 지도 API로 재검증) |

워크플로 기본값은 `scenario_990002`다. 실행 전 입력 화면의 여덟 값을 모두 직접 대조한다. `reset-game-server.yml`은 외부 시나리오 디렉터리를 재생성하거나 기존 파일을 유지할 수 있으므로, 이미지의 `scenario_990002`가 실제 시드에 사용될 경로까지 확인한다.

## 전환 직전 실측과 중단 조건

1. `GET /admin/env/servers/pep`와 `GET /api/admin/game-settings`에서 현재 시나리오·세대·턴텀·월드 상태를 기록한다. `GET /api/servers`와 `GET /api/server-basic-info/pep`의 공개 표기도 대조한다.
2. 운영 호스트의 디스크 여유, PostgreSQL·Redis 백업 가능 공간, 기존 백업 위치를 확인한다. 백업은 켠다.
3. `GET /admin/turn-daemon/status`의 `lastTickError`, `failedTicks`, `consecutiveFailures`, `lastTickCompletedAt`, `lastSuccessfulTickAgeSeconds`를 확인한다. `/actuator/health`의 UP만으로 턴 루프 정상으로 판정하지 않는다. 오류 내용에 월드 데이터가 있을 수 있으므로 공개 기록에는 예외 종류와 카운터만 남긴다.
4. 최신 main의 필수 CI 8개가 모두 초록인지 확인하고, S3 종료의 W0–W4 증거(관문·적색 짝의 동일 SHA 3회 비교, 단위·모의 결과, 화면 테스트, 로컬 스택 화면 9종·API·DB 사건표·실측 조우 건수·재점령 0건과 `tick failed=0`)를 전환 기록에 연결한다. 하나라도 미통과면 전환을 중단한다.
5. 승격할 정확한 SHA와 이미지 digest, 시나리오 SHA, 지도 城 수·첫 城, 직전 운영 상태를 기록한다. #865 병합 여부가 바뀌면 입력값과 합성 시나리오를 다시 만든다.

코드 정리와 새 계약 검증을 완료하고 대상별 사용자 승인을 받은 뒤 승격과 월드 초기화를 한 작업으로 추적한다. 완료 뒤에는 새 월드의 시나리오·城 수·첫 城, 사람 가입·출사, 순 증가, `lastTickError`와 `failedTicks`, 화면과 API를 다시 검증한다. 실패하면 워크플로의 복구 상태와 백업을 기준으로 복구 절차를 결정한다.

## 컷오버 전 이미지와 백업으로 복원

전환 직전에 실행 중인 `IMAGE_TAG`·`WEB_GAME_TAG`의 정확한 SHA와 이미지 digest를 고정하고, 해당 SHA 이미지를 레지스트리 정리 대상에서 영구 제외한다. 같은 시점의 DB 백업과 외부 시나리오 파일 사본을 보존하고 복원 가능성을 확인한다. 장애 복구는 이 이미지 세트와 백업을 **함께** 복원한 뒤 API 노출 전에 세계 형식, 턴 시계, 데이터 일관성을 검증한다. 퇴역한 애플리케이션 롤백 스위치는 복구 절차에 사용하지 않는다. 실제 복원과 운영 데이터 변경은 대상별 사용자 승인을 받는다.
