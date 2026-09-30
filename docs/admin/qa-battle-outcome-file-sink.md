# 격리 QA 전투 결과 파일 sink

`BattleOutcomeBatchSink`의 파일 구현은 기본적으로 등록되지 않는다. 합성 `scenario_990002` 격리 엔진에서만 다음 환경값을 설정한다.

```sh
QA_BATTLE_OUTCOME_FILE_ENABLED=true
QA_BATTLE_OUTCOME_FILE_DIRECTORY=/qa-evidence/battles
```

디렉터리는 컨테이너 안의 절대 경로이며 격리 증거 디렉터리로 마운트한다. 설정이 켜졌는데 엔진 world ID가 `990002`가 아니면 시작을 거절한다. 기본 제품 경로에는 sink bean이 없다.

성공한 JDBC flush 뒤 sink가 받은 각 조우는 `battle-<worldId>-<encounterId SHA-256>.json` 한 파일로 기록한다. JSON에는 `qa-committed-battle-v1` 스키마, flush generation, 조우 ID, 전장·규칙 해시, 결과·승자·지휘관 상태·라운드·replay hash·실제 callback 관측값을 담는다. 임시 파일을 같은 디렉터리에 쓰고 동기화한 다음 hard link로 공개한다. 같은 bytes의 재시도는 허용하고 다른 bytes의 재시도는 실패한다. 부분 batch 실패 시 이미 공개한 조우는 유지되므로 생산자가 보존한 batch를 다시 보낼 수 있다.

파일의 존재만으로 W4를 통과시키지 않는다. 수집기는 같은 정확 제품 SHA의 DB 봉인 조우 ID 및 커밋된 `general.meta.lastBattle`의 ID/replayHash와 대조하고, 비어 있는 결과·누락·중복·불일치·무승자 분류·월 경계 실패를 거절해야 한다.
