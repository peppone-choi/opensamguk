# 입력 배달 증거 게이트 (E10)

`data/commands/input-catalog.json`의 v5 행은 D21 첫걸음 **설명**을 위한 `firstStepsExplanationStepId`·`firstStepsExplanationNaReason`과 `evidence`를 가진다. 단계 값은 여덟 `tutorial.*` 글 ID, `UNMAPPED`(K7 화면 대응 확인 전), `N/A`(설명 밖임을 확인) 중 하나다. `N/A`에는 비어 있지 않은 사유가 필요하고, 나머지는 사유가 `null`이다. 모든 `N/A` 선언은 배달 단계와 단계별 증거 유무에 관계없이 아래 제외 원장의 동일 입력·사유, `CONFIRMED` 상태, 출처를 검사한다. v4 `tutorialObjectiveId`·`tutorialNaReason`은 진척 판정용 폐기 필드다. 런타임 파서는 v4의 `HELP_READY` 이하 행만 `UNMAPPED`로 읽어 전환할 수 있고, v5 행에서 폐기 필드를 거절한다. 현재 pinned 74행은 첫걸음 글 연결 7건, 첫걸음 설명 밖 38건, 플레이어 전달 단계 `PLANNED` 29건이다. 대응표 연결은 설명 범위의 판단이며 글 승인·화면 바로가기 검증·입력 단계 승격을 뜻하지 않는다.

## 동결 기준선과 부채

`data/commands/input-delivery-baseline-v3.json`은 v3의 **pinned 74개** 상태를 고정한다(PLANNED 29, HANDLER_READY 13, UI_READY 32). 이 74개는 총 행 수의 상한이 아니다. 새 입력이 등록되면 현재 카탈로그의 전체 행을 검증하되 pinned ID와 기준 상태를 보존한다. SHA-256을 `tools/ci/input_evidence_gate.py`에 고정하여 기준선 재생성을 거절한다. 기존 45개 READY 행에는 이전 단계의 단계별 증거가 아직 없으며, `data/commands/input-evidence-debt-v1.json`에 각각 기록한다. 이 상태를 임의로 내리거나 증거 없이 `VERIFIED`로 간주하지 않는다. 기존 상태까지 소급 증명하면 부채 목록을 같은 변경에서 갱신한다.

`INPUT_PLANNED`로 첫걸음 설명에서 제외한 행은 `deliveryState: PLANNED`일 때만 유효하다. 도메인·핸들러 등 다음 단계의 증거를 붙여 승격하기 전에 K7과 설명 연결 또는 다른 확정 제외 사유를 재판정한다. 게이트는 이후 단계에 남은 `INPUT_PLANNED`와 이를 `tutorial-na:INPUT_PLANNED` 완료 증거로 사용하는 시도를 거절한다.

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

UI 소스 검사는 도구 전용 TypeScript 5.7.2 AST 파서를 사용한다. 먼저 `npm ci --prefix tools/ci --ignore-scripts --no-audit --no-fund`로 고정 lock을 설치한다. 파서가 없으면 실패하며 skip이나 전체 파일 문자열 검색으로 대체하지 않는다. spec을 import/eval하지 않는다. TypeScript 구문 오류와 지원하지 않는 제어 흐름/동적 바인딩은 미확인으로 거절한다. 다른 proof role의 검사와 D21 설명 역할은 그대로다.

## UI 입력 증거의 제출 계약

`ui-e2e` 참조의 앵커는 정확한 `inputId`다. 시험 제목은 다음처럼 대괄호로 그 사례의 입력을 지정한다.

```ts
test('[action.farm] 농지 개간: 대상 고르고 보낸다', …)
```

이 제목만으로 입력 실증이 되지는 않는다. 같은 시험 사례 안에 화면의 앵커/단추, 실제 조작과 보내기, 그 조작에서 발생한 POST의 정확한 경로 및 본문 단언이 있어야 한다. 주석·대역 자료·다른 시험의 조작이나 요청·`action.farmX`는 `action.farm`의 증거가 아니다. 준비 중 입력의 차단 화면을 확인한 시험은 POST 전달 증거와 구분한다.

서버 계약은 입력 ID에 직접 연결한다. `court.reward`는 조정 `court/reward` 요청과 retainerId/money를, `work.start`는 공사 `work/start` 요청과 countyId/work를 검증한다. 화면 주소 `/game/join`은 출사 POST 경로의 증거가 아니다. 점을 slash로 치환해 경로를 추정하거나 표시명으로 입력 별칭을 만들지 않는다. 매개변수 사례는 inputId·경로·기대 본문이 같은 명시적인 사례 행에 연결돼야 한다. helper는 실제 조작과 요청 단언을 수행하는 구현 및 해당 사례의 호출 인수가 확인돼야 한다. 안전하게 연결할 수 없는 동적 사례는 미확인으로 남긴다.

### 정적 검사와 실행 결과

정적 소스 검사 합격은 실행 결과가 아니다. `@both`는 모바일 CI 선택에 쓰는 태그이며, 두 기기에서 시험이 성공했다는 증거가 아니다. 입력의 UI 실증에는 같은 후보 head의 다음 증거를 함께 보존한다.

- 선택한 spec·정확한 사례 제목·inputId와 source/helper hash.
- CI workflow·run/attempt·후보 head와 실제 실행 소스의 바인딩.
- 실제 Playwright desktop와 mobile 사례 성공, skip 0, 정상 종료 코드와 완료된 phase 기록.
- 해당 요청의 정본 경로/본문을 같은 사례에서 단언한 결과.

한 프로젝트만 실행, skip/fixme, 미실행, 결과 파일 부재, 다른 head의 성공은 완료 증거가 아니다. Playwright report·phase 기록·로그는 CI artifact와 메타 evidence에 보존한다. 정적 검사나 합성 report 회귀를 실제 브라우저 실행 PASS로 보고하지 않는다. 증거가 없는 기존 45개 부채와 pinned 74개 기준선은 이 제출 형식만으로 승격·탕감되지 않는다. 첫걸음 설명(D21)과 다른 proof role의 의미도 유지한다.

실행 증거 진입점은 다음과 같다. CI 배선은 phase의 실제 workflow/run/attempt/event/repository metadata와 Git 객체를 함께 공급해야 한다.

```sh
python3 tools/ci/input_evidence_gate.py --ui-runtime \
  --phase <phase.json> --results <results.json> \
  --github-event <GITHUB_EVENT_PATH> --receipt <ui-proof-receipt.json>
```

`candidateSha`는 event 원본의 논리 PR head이며 `actualCheckoutSha`는 실제 runner git HEAD다. 기본 PR merge checkout을 유지하며, 실제 base와 merge 부모 `[base,candidate]`를 대조한다. sourcePins는 spec·상대 import helper·parser·route/args binding의 candidate Git blob, checkout Git blob, working SHA-256이 모두 같아야 한다. 필요한 Git 객체가 없거나 working 파일이 달라도 인증하지 않는다. producer는 현재 CI의 공개 metadata와 event/phase를 대조한다. 다른 head의 예전 실행은 같은 바이트라는 이유로 재사용하지 않는다.

schemaVersion 1 receipt는 producer, candidateSha, actualCheckoutSha, baseSha, checkoutParents, sourcePins, proofs, status, reasons를 가진다. `UI_RUNTIME_VERIFIED`는 선택된 사례의 실제 desktop/mobile 성공이다. `NO_UI_PROOFS`는 선택한 증거가 없다는 뜻이며, 먼저 phase와 smoke report 실패를 확인한다. `FAILED`/`UNAVAILABLE`은 nonzero이며 실패 receipt도 남긴다. 재시도 성공/flaky는 확정 성공으로 바꾸지 않는다. 모든 입력의 검증 또는 게임 명령 실행 성공을 이 receipt 하나로 선언하지 않는다.

현재 명시적 route/args binding 지원은 `court.reward`, `court.dispatchReply`, `work.start`, `action.enlist`의 지정 대상 모드, `action.deploy`, `action.move`, `action.search`, `action.employ`, `action.farm`이다. 바인딩하지 않은 입력을 generic 경로로 추정하지 않고 `UNSUPPORTED_UI_PROOF`로 거절한다. 다른 canonical 변형은 실제 controller/args 계약을 대조해 추가한다. `press/BOTH`는 검토한 parity helper를 사용하며 press/isMobile 함수가 달라지면 재검토가 필요하다. literal 독립 시험과 const 사례 배열/for-of template 제목, 직선 실행의 request/body 연결을 지원한다. 조건부 request/본문 단언, try/조기 종료, 동적 helper는 증거를 빌리지 않는다.
