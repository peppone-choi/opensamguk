# 개인 턴 선후 지표 G6 — test-only 게이트

관련: #893 / OPENSAM-292. 정본: `docs/superpowers/specs/2026-09-18-personal-turn-determinism-contract.md` §2.4, §3 G6.

## 경계

HWIHA 합성 장수·부곡과 기존 CampaignWorldFixture 지도에서 실제 개인 턴 lifecycle 및 EncounterResolver.resolvePending을 실행한다. 제품 소스·DB·API·SAMMO 골든을 변경하지 않는다. 계측은 provisional 테스트 자료이며 제품 flush/영속성/공개를 증명하지 않는다. **Q5 제품 저장 자리·공개 범위는 사용자 미결**이다. #893 전체 완료가 아니라 G6 test-only 부분 구현이다.

순번은 observeGeneralTurnStart의 실제 호출 순서(1부터)다. 삽입 순서를 뒤집은 동률 장수를 (turnTime,generalId) 정렬과 대조하며 observeHandledTurn과 완료 결과도 비교한다. 방어자 턴이 뒤에 오는 경우를 위해 조우 관측을 수집한 다음 drain 종료 뒤 순번을 join한다. 방어자 턴이 없는 경우 ordinal=null, 사유=NO_PERSONAL_TURN_IN_PHASE로 명시한다. 동률 그룹 크기와 ID 순위는 같은 turnTime의 초기 장수 집합으로 계산한다.

보고서 행은 worldId, 연·월·순, encounterId, 양측 generalId/순번/동률 정보, outcome, winners를 포함한다. 설치·대응 계책, 요격·회피 발동을 기존 BattleOutcomeObservation에서 얻을 수 없으므로 각각 UNAVAILABLE이다. 0/false/null로 추정하지 않는다. 후속 제품 계측 구현은 별도 범위다.

## 시험과 보고서

GitHub-hosted runner에서 실행한다(로컬 빌드 금지).

```sh
./gradlew :app:game-engine:test --tests opensamguk.engine.invariance.G6TurnOrderMetricTest --no-daemon
OPENSAMGUK_G6_PROBE=DROP_HOOK ./gradlew :app:game-engine:test --tests opensamguk.engine.invariance.G6TurnOrderMetricTest --rerun-tasks --no-daemon
```

정상: 실제 조우 1건→행 1건, 같은 입력 2회 JSON 바이트 동일, 무조우→0행. Unavailable/Disbanded는 완료 전투가 아니므로 0행이며 실제 Resolution을 검사한다. 별도 방어자 미실행 시험도 포함한다.

DROP_HOOK: BattleOutcomeObserver.NONE을 주입하여 실제 전투는 정산되지만 행은 0건이 된다. 동일 assertOneEncounter의 행 수 단언이 실패해야 한다. 영구 negative control은 이 실패를 assertFailsWith로 확인하므로 정상 CI는 초록이어야 한다. 환경변수 RED 실행은 정상 조우/결정론 시험을 실제 실패시킨다.

자료 경로: `app/game-engine/build/reports/g6-turn-order/*.json`, JUnit XML `app/game-engine/build/test-results/test/TEST-opensamguk.engine.invariance.G6TurnOrderMetricTest.xml`. 정상과 RED 자료는 별도 디렉터리에 보존하고 RED 후 정상 실행을 복원해야 한다. 현재 기존 CI에는 G6 정상 XML/JSON 업로드 및 환경변수 RED 별도 실행 단계가 없어 후속 CI 소유 조율이 필요하다. 본 PR은 승인된 신규 3파일 소유만 유지한다.

기존 G1/G3/G4, EncounterResolverTest, 전체 game-engine 및 jvm-core는 수정 없이 기존 CI로 검증한다. 제품 QA·실서버·GCP·게임 서버 업데이트/초기화는 NOT_RUN이다.
