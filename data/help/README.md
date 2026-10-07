# 도움말 저장소

`topics.json`은 입력 원장의 `helpTopicId`마다 하나의 주제를 둔다. 입력 이외의 글은 `topic-registry.json`에 ID와 종류(`TUTORIAL` 또는 `CONCEPT`)를 등록한다. 첫걸음 설명은 `tutorial.signup`, `tutorial.createGeneral`, `tutorial.enlist`, `tutorial.dispatch`, `tutorial.work`, `tutorial.employ`, `tutorial.march`, `tutorial.battle`의 여덟 글이다. `failure-reasons.json`은 원장의 모든 `failureReasons` 코드에 설명과 회복 조언을 둔다. 여러 입력에서 뜻이 달라지는 사유는 `byInputId`에 해당 입력의 문구를 추가한다.

사용자 결정 D144에 따라 작성한 도움말·튜토리얼 글은 작성 시점에 `reviewState=APPROVED`로 기록하며, 사용자가 추후 문구를 일괄 수정할 수 있다. 이번 첫걸음 여덟 글도 `APPROVED`다. 기존 입력 도움말의 `DRAFT` 행은 이 변경에서 일괄 전환하지 않는다. `DRAFT`와 `APPROVED`는 사람 글의 상태이며 입력의 제공 여부, 실행 준비 상태, 접수·실행 성공 판정과 무관하다.

D144의 UI·AI·HELP·TUTORIAL·REPLAY 증거 단계는 서로 독립적이며 기록 순서를 강제하지 않는다. 글의 `APPROVED`가 전체 기능의 `VERIFIED`를 뜻하지 않으며, `VERIFIED` 최종 관문에는 필요한 모든 증거가 갖춰져야 한다.

주제의 `sections`에는 설명, 예시, 성공 예, 실패 예, 회복 조언을 쓴다. 비용·권한·대상·시기 같은 기계 판정과 수치는 `data/commands/input-catalog.json`이 정본이며 이 글에 복제하지 않는다. 문맥 도움말 API가 원장의 현재 필드를 함께 반환한다. 아직 제공되지 않은 입력은 주제를 읽을 수 있어도 실행 가능하다고 쓰지 않는다.

첫걸음은 사용자 결정 D21에 따른 설명과 화면 바로가기 안내다. 진행 기록·완료 판정·연습 월드가 없으며, 장수 생성과 전투 등 준비 중인 기능은 글에서도 준비 중으로 명시한다.

역사적 맥락을 주장할 때는 `sources`에 사료의 책·권을 적는다. 정사 `CHRONICLE`과 연의 `ROMANCE`를 구분하고, 출처 없는 역사 서술은 추가하지 않는다. 현재 글은 게임 규칙과 화면 안내만 담아 역사적 주장을 쓰지 않는다.

저장소 로더는 입력 원장의 주제와 등록부의 추가 글을 함께 검사하며, 고아·중복 주제와 깨진 관련 링크를 거절한다. 새 입력이나 사유가 생기면 저장소 문구를 같은 PR에서 추가해야 한다. 검증에서는 모든 원장 ID 연결, 등록된 추가 글, 모든 실패 사유 설명, 숫자 규칙 복제 금지를 확인한다.
