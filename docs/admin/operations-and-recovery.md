# 운영·복구 매뉴얼

> 상태: 현재 GCP/shared-server 운영 경계 기준
> 마지막 검토: 2026-08-20

## 승인 경계

다음은 각각 별도 승인 대상입니다.

- commit, push, PR, merge
- production workflow dispatch와 deploy
- 서버 reset·delete와 운영 데이터 삭제
- secret 접근·변경
- DB migration 적용과 restore

한 작업의 승인이 다른 작업의 승인을 자동으로 포함하지 않습니다.

## 복구 준비 상태: `UNKNOWN / blocked`

현재 이 저장소의 [reset workflow](../../.github/workflows/reset-game-server.yml)는 reset 전 PostgreSQL 백업을
만드는 경로는 포함하지만, 그 백업을 새 격리 DB에 복원하고 검증하는 정본 restore runbook은 제공하지
않습니다. 별도 운영 저장소의 승인된 runbook, 백업 보존 위치·기간, 암호화·접근 주체, RPO/RTO, restore
명령과 성공 판정이 확인되기 전에는 production reset/delete를 실행하지 않습니다.

이 문서의 복구 체크리스트는 restore 명령을 대신하지 않습니다. 실행 가능한 정본 절차가 확인될 때까지
reset/delete는 `blocked`이며, 관리자 화면이나 workflow의 존재를 실행 승인으로 해석하지 않습니다.

## 배포 전 체크리스트

1. 대상 저장소, 서버 ID, branch와 immutable tag/SHA를 기록합니다.
2. GitHub CI와 필요한 로컬 검증이 현재 SHA에서 통과했는지 확인합니다.
3. `gcp-prod` runner와 대상 VM/container가 정상인지 확인합니다.
4. disk 용량과 Docker image/volume 여유를 확인합니다.
5. 승인된 DB backup/restore runbook, 보존 범위와 Flyway 현재/목표 버전을 확인합니다. 없으면 중단합니다.
6. server env의 scenario code와 world ID를 값 노출 없이 확인합니다.
7. rollback 가능한 앱 버전과 schema 호환성을 확인합니다.
8. 점검 공지와 관측 담당자를 정합니다.

## 배포 후 체크리스트

- nginx `/health`
- gateway-api, game-api, game-engine health
- 로그인 → 로비 → 대상 서버 입장
- read API와 SSE 연결
- 명령 1건의 접수 → terminal 결과 → 권위 read 반영
- `world_state`의 현재 연·월 또는 턴 시각 전진
- 서버별 game-api/game-engine 버전 skew 없음
- 로그에 secret·개인정보 노출 없음

local unit, Testcontainers와 Docker smoke는 production 네트워크·runner·DNS·실데이터 전환을 증명하지 않습니다.

## 활성 월드 지도 정체성 복구

한 번 seed된 월드에서 `mapName`은 불변의 숫자 city-id 공간을 식별합니다. 생성된 도시의 수나 순서를
바꾸려면 새 버전 지도 키를 사용합니다. 기존 월드를 새 키로 조용히 옮기거나 ordinal id 추측으로 고치지
않습니다.

`han-780-v1`는 과거 780성 한나라 월드의 호환 지도 키입니다. V45는 활성 지도 키가 `han`인 월드만
검사하며, 정확히 `1..780`인 city-id 모양만 `han-780-v1`로 고정합니다. 정확히 `1..774`인 기존 한나라
월드는 변경하지 않습니다. 그 밖의 city 수·최솟값·최댓값 모양은 모호하므로 fail-closed로 중단하며,
ordinal id를 추측해 수리하지 않습니다.

V45 또는 그 앱 버전을 production에 적용하기 전에는 승인된 DB backup과 restore runbook 증거가 반드시
있어야 합니다. 적용 뒤에는 다음 네 신호를 두 번의 관측으로 확인합니다.

- engine health가 `UP`
- `successfulTicks`가 증가
- `consecutiveFailures`가 `0`
- public game time이 전진

V45 뒤 image-only rollback은 안전하지 않습니다. 이전 image와 V45 전 DB backup을 함께 복원하는 승인된
경로만 사용합니다. `ng_games.map`, city/gameplay id를 수동으로 바꾸거나 V45·validator 조건을 약화하지
않습니다.

## 대표 장애 대응

### Han V3 신규 세계와 V2 저장 세계 구분

`han`과 `han-world-v2`는 기존 774개 도시의 ID 공간입니다. V47의 V2 명칭을 새 도시 원장으로
재해석하지 않습니다. `han-world-v3`는 검토된 781개 route node를 사용하는 **신규 세계 전용** 지도입니다.
역사 시나리오 15개의 새 템플릿이 V3를 가리켜도, 이미 저장된 V2 세계는 그 템플릿으로 재시드하지 않습니다.

- 기존 `han-780-v1` 호환 자산과 V45/V47 migration을 수정하지 않습니다.
- 숫자 ID 수만 맞추거나 `mapName`만 바꾸는 수동 전환은 금지합니다. 지점의 physical ref와 stable
  route key까지 달라질 수 있습니다. 운영 세계의 V3 전환·reset은 별도 승인과 복구 계획이 필요합니다.
- V3 배포 후보는 `build_han_world.py --target han-world-v3 --check`,
  `apply_han_world.py --map han-world-v3 --check`,
  `audit_han_supply_disagreements.py --map han-world-v3 --check`를 모두 통과해야 합니다.
- 공급 보호 원장은 지도별로 구분합니다. V3 원장을 legacy 숫자 ID에 적용하거나 반대로 적용하지 않습니다.
- 수역 overlay는 정확한 land tile SHA와 manifest에 묶입니다. 해시 불일치를 건너뛰지 말고 동일한
  검토 산출물 세트로 되돌립니다. 현재 항구·강 통과점 근거가 없어 실행 가능한 수운 간선은 없으며,
  수역이 보인다는 이유만으로 항행·수전이 활성화된 것으로 해석하지 않습니다.

이 절은 배포/DB 전환 명령이나 승인을 대신하지 않습니다.

### Han V3 수역 상태와 보급 복구

V49는 세계별 `water_zone_control` 빈 테이블을 추가합니다. V3 부팅은 실제 snapshot loader에서
해당 세계의 행만 읽고 지형 버전·해시·수역 ID를 검증합니다. 행 없음은 미확인이며 육지 소유권에서
통제를 만들지 않습니다. V2 저장 세계는 수역 테이블을 조회하지 않습니다.

- V901을 이미 적용한 실험 DB는 낮은 버전 V49의 별도 업그레이드 검증이 필요합니다.
  `outOfOrder` 전역 활성화, Flyway history repair, DB reset으로 이 단계를 건너뛰지 않습니다.
- 수역 변경은 daemon recorder와 기존 JDBC transaction으로만 저장합니다. 한 틱의 여러 변경은
  최초 기대 revision과 최종 상태로 합칩니다. 전송 오류 재시도는 보존한 동일 payload를 사용합니다.
- `StaleWaterControlException`은 재로드가 필요한 동시 수정 충돌입니다. 동일 명령을 계속 재시도하거나
  revision 값을 수동으로 올리지 말고 기존 격리·재로드 절차를 따릅니다.
- 수운 보급은 명시된 통과 허가·가용 용량과 자국 수역 통제, 계절 조건을 모두 요구합니다.
  현재 실제 함대 용량 공급자는 없으므로 정적 간선 capacity만으로 수운을 열지 않습니다.
- 기존 두 그래프의 보급 안전 판정을 유지합니다. 수역 통제 변경은 토지 소유권을 직접 바꾸지 않습니다.
- 기존 legacy full-rehydrate 서명만으로 수역의 DB roundtrip이나 replay를 검증했다고 간주하지 않습니다.
  수역 전용 PostgreSQL 복구·CAS rollback 검증을 배포 전에 별도로 실행해야 합니다.

### 서비스는 online인데 화면이 502

nginx 정적 upstream의 stale DNS, 대상 container health와 포트를 확인합니다. shared 서비스 변경 뒤 nginx를
마지막에 재시작하는 운영 순서를 따릅니다.

### 명령은 접수됐는데 결과가 없음

durable inbox, Redis wake, engine claim, flush, result/outbox, XACK 순서에서 요청 ID를 추적합니다. Redis 알림은
DB commit의 대체 진실이 아닙니다.

### engine 재기동 뒤 상태가 과거로 돌아감

새로운 write를 멈추고 world ID, 마지막 version, snapshot loader와 flush/recovery 상태를 확인합니다. 현재
DB를 추측으로 고치지 말고 restart-rehydrate 증거와 quarantine 절차를 따릅니다.

### OOM 또는 반복 재시작

최근 [OPENSAM-217/#477](https://github.com/peppone-choi/opensamguk/issues/477)의 Compose restart·메모리
계약과 현재 JVM/container 한도를 대조합니다. 구형 로컬 `.env`에서 world ID가 빠졌는지도 확인하되 실제 값을
출력하지 않습니다.

### migration 실패

추가 migration을 재시도하기 전에 실패한 버전, transaction 여부, 적용된 schema history와 앱 호환성을
확인합니다. 이미 릴리스된 migration 파일을 수정하지 않고 새 전진 migration으로 수리합니다.

## 복구 원칙

- 앱 rollback과 DB rollback을 같은 것으로 취급하지 않습니다.
- Flyway migration은 자동 역실행하지 않습니다.
- 승인된 restore runbook이 생기면 새 격리 DB에서 먼저 연습하고 행 수·world ID·핵심 read를 대조합니다.
- reset/delete가 commit된 뒤에는 “재시도”가 중복 삭제·정산을 만들지 않는지 확인합니다.
- 복구 뒤 로그인, 권한, 명령, SSE와 턴 전진을 실제 사용자 경로로 다시 확인합니다.

## 에스컬레이션에 필요한 정보

- 발생 시각과 서버 ID
- 배포 SHA/tag와 서비스별 버전
- request ID 또는 operation ID
- health와 오류 메시지의 비밀 제거본
- 마지막 정상 관측과 최초 실패 관측
- 실행한 조치와 결과

토큰, 비밀번호, 실제 `.env`, 사용자 개인정보는 첨부하지 않습니다.
