# 입력 배달 증거 게이트 (E10)

`data/commands/input-catalog.json`의 v5 행은 D21 첫걸음 **설명**을 위한 `firstStepsExplanationStepId`·`firstStepsExplanationNaReason`과 `evidence`를 가진다. 단계 값은 여덟 `tutorial.*` 글 ID, `UNMAPPED`(K7 화면 대응 확인 전), `N/A`(설명 밖임을 확인) 중 하나다. `N/A`에는 비어 있지 않은 사유가 필요하고, 나머지는 사유가 `null`이다. 모든 `N/A` 선언은 배달 단계와 단계별 증거 유무에 관계없이 아래 제외 원장의 동일 입력·사유, `CONFIRMED` 상태, 출처를 검사한다. v4 `tutorialObjectiveId`·`tutorialNaReason`은 진척 판정용 폐기 필드다. 런타임 파서는 v4의 `HELP_READY` 이하 행만 `UNMAPPED`로 읽어 전환할 수 있고, v5 행에서 폐기 필드를 거절한다. 현재 pinned 74행은 첫걸음 글 연결 7건, 첫걸음 설명 밖 38건, 플레이어 전달 단계 `PLANNED` 29건이다. 대응표 연결은 설명 범위의 판단이며 글 승인·화면 바로가기 검증·입력 단계 승격을 뜻하지 않는다.

## 동결 기준선과 부채

`data/commands/input-delivery-baseline-v3.json`은 v3의 **pinned 74개** 상태를 고정한다(PLANNED 29, HANDLER_READY 13, UI_READY 32). 이 74개는 총 행 수의 상한이 아니다. 새 입력이 등록되면 현재 카탈로그의 전체 행을 검증하되 pinned ID와 기준 상태를 보존한다. SHA-256을 `tools/ci/input_evidence_gate.py`에 고정하여 기준선 재생성을 거절한다. 기존 45개 READY 행에는 이전 단계의 단계별 증거가 아직 없으며, `data/commands/input-evidence-debt-v1.json`에 각각 기록한다. 이 상태를 임의로 내리거나 증거 없이 `VERIFIED`로 간주하지 않는다. 기존 상태까지 소급 증명하면 부채 목록을 같은 변경에서 갱신한다.

## 승격 방법

신규 행은 `PLANNED`부터, 기존 행은 동결된 상태부터 연속된 다음 단계의 증거가 있어야 올라간다. `deliveryState`는 이 증거로 계산된 최고 단계와 같아야 한다. 단계 하나를 건너뛰거나 선언만 올리면 CI가 실패한다.

```json
"evidence": {
  "UI_READY": ["ui-e2e:web/game/e2e/example.spec.ts#action.example"],
  "AI_READY": [
    "ai-selector:app/game-engine/src/main/kotlin/example/Example.kt#action.example",
    "ai-test:app/game-engine/src/test/kotlin/example/ExampleTest.kt#action.example"
  ],
  "HELP_READY": ["help-topic:data/help/topics.json#commands.action.example"]
}
```

각 참조는 `역할:저장소 상대경로#앵커` 형식이다. 게이트는 파일 존재, 역할별 허용 경로, 앵커와 입력 ID를 검사한다. 단계별 역할은 `domain-rule`, `handler-test`, `ui-e2e`, `ai-selector`와 `ai-test` 양쪽, `help-topic`, `tutorial-step` **및** `tutorial-shortcut`(연결 입력) 또는 `tutorial-na`(해당 없음), `replay-test`, `campaign-test`다. `help-topic`은 해당 토픽 ID와 `reviewState: APPROVED`도 요구한다. 연결된 첫걸음은 `tutorial-step:data/help/topics.json#tutorial.<step>`의 승인된 글과 `tutorial-shortcut:web/game/e2e/<test>#tutorial.<step>`의 실제 화면 검증이 모두 필요하다. 해당 없음은 `firstStepsExplanationNaReason`에 확정 사유를 쓰고 `tutorial-na:<사유>`로 연결한다. 이때 `data/help/first-steps-exclusions-v1.json`에 같은 입력·사유의 `CONFIRMED` 행과 입력별 절을 가진 [`first-steps-exclusions-v1.md`](first-steps-exclusions-v1.md)의 출처가 있어야 한다(현재 확정 67행). `UNMAPPED`와 옛 `E9_PENDING_U3`은 `TUTORIAL_READY`나 그 뒤 `VERIFIED`의 증거가 아니다.

검사는 `python3 tools/ci/input_evidence_gate.py` 또는 `python3 -m unittest discover -s tools/ci -p 'test_*.py'`로 실행한다. CI의 `contracts` 작업이 후자를 실행한다. 원장 파서는 v5 필드 형식과 중복 키를 검사하고, `AiPolicyRegistry`는 AI_READY 이상인 행에 실제 selector 바인딩을 요구한다.
