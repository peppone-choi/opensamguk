# 도움말·튜토리얼 서버 API 계약

상위 목표: [공개 튜토리얼 흐름](../superpowers/specs/2026-09-17-general-and-retinue-campaign-redesign.md) §15.2, [도움말 완료 조건](../superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md) §8. 서버 구현 추적: OPENSAM-293~295 / #961~963. 이 문서는 **계약**이며 엔드포인트가 이미 제공된다는 뜻이 아니다.

## 공통 규칙

- 경로는 game-api의 `/api` 아래다. JSON 필드는 camelCase, 문자 인코딩은 UTF-8이다. 화면은 기존 gateway 프록시와 서버 선택 규약을 따른다. `/hwiha`는 ADR-LITE-066의 리다이렉트 전용 경로다.
- `ruleProfile=HWIHA` 월드에서만 제공한다. 다른 규칙 월드는 404 `WORLD_PROFILE_UNAVAILABLE`, 활성 월드가 없으면 503 `WORLD_UNAVAILABLE`이다.
- 성공 응답의 `schemaVersion`은 정수 `1`이다. 오류 응답은 `{ "error": { "code": string, "message": string } }`이다. 미확인 주제·입력·사유는 404, 잘못된 검색 질의는 400이다.
- `inputId`, `helpTopicId`, `failureReason`, `firstStepsExplanation.stepId`는 식별자다. 화면은 반환된 제목·설명·회복 조언을 표시한다. 비용·권한·대상·시기는 현재 `InputCatalog` 원장 행에서 투영하며 사람 글에 복제하지 않는다.
- 정적 도움말 읽기는 로그인 없이 가능하다. D21 첫걸음은 본 서버를 설명하는 여덟 단계 글과 실제 화면 바로가기다. 연습 월드·진척 API·달성 판정·전용 사건은 없다.
- `GET`은 상태를 바꾸지 않는다. 첫걸음 설명의 단계 ID는 진행 상태나 달성 증거가 아니다.

## 응답 타입

```ts
type ErrorResponse = { error: { code: string; message: string } };
type HelpSection = {
  explanation: string; example: string; successExample: string;
  failureExample: string; recoveryAdvice: string; historicalContext?: string | null;
};
type HistoricalSource = {
  tradition: 'CHRONICLE' | 'ROMANCE'; work: string; book: string; passage?: string | null;
};
type HelpTopic = {
  id: string; title: string; reviewState: 'DRAFT' | 'APPROVED'; sections: HelpSection;
  sources: HistoricalSource[]; relatedTopicIds: string[];
};
type HelpTopicResponse = { schemaVersion: 1; topic: HelpTopic };
type HelpTopicSummary = {
  id: string; title: string; reviewState: 'DRAFT' | 'APPROVED';
  group: 'INPUT' | 'CONCEPT' | 'TUTORIAL';
  inputId: string | null; inputKind: InputContract['kind'] | null; excerpt: string;
};
type HelpTopicListResponse = { schemaVersion: 1; topics: HelpTopicSummary[] };
type HelpSearchHit = { id: string; title: string; reviewState: 'DRAFT' | 'APPROVED'; excerpt: string; matchedSection: string };
type HelpSearchResponse = { schemaVersion: 1; query: string; hits: HelpSearchHit[] };
type InputContract = {
  inputId: string;
  kind: 'GENERAL_ACTION' | 'PLACEMENT' | 'POLICY' | 'WORK' | 'STRATAGEM' | 'COURT_DECISION';
  displayName: string | null; deliveryState: string; actor: string; authorityRule: string;
  targetSchema: Record<string, unknown>; costSchema: Record<string, unknown>;
  timing: Record<string, unknown>; effectScope: string; failureReasons: string[];
  helpTopicId: string;
  firstStepsExplanation: {
    state: 'UNMAPPED' | 'NOT_APPLICABLE' | 'LINKED';
    stepId: string | null; naReason: string | null;
  };
};
type ContextHelpResponse = { schemaVersion: 1; topic: HelpTopic; input: InputContract };
type FailureHelpResponse = {
  schemaVersion: 1; reason: string; reviewState: 'DRAFT' | 'APPROVED'; explanation: string;
  recoveryAdvice: string; relatedTopicIds: string[];
};
type CreateGeneralRequest = {
  expectedWorldId: number;
  clientRequestId: string;
  choice:
    | {
        kind: 'CUSTOM'; name: string; nativeCountyId: number;
        stats: { leadership: number; strength: number; intel: number; politics: number; charm: number };
        ideologyId: 'WANGDO' | 'PAEDO' | 'ADO' | 'HALGEO' | 'MYEONGRI' | 'YEGYO';
        traitId: 'DISCIPLINE' | 'WATER_COMBAT' | 'RENOWN' | 'DEBATER' | 'STRATEGIST' | 'SINGLE_RIDER';
      }
    | { kind: 'HISTORICAL'; historicalGeneralId: number };
};
type CreateGeneralAccepted = {
  schemaVersion: 1; status: 'ACCEPTED'; requestId: string; worldId: number;
};
type CreateGeneralResult = {
  schemaVersion: 1; requestId: string; worldId: number;
  status: 'PENDING' | 'CREATED' | 'REJECTED';
  generalId: number | null;
  error: ErrorResponse['error'] | null;
};
```

`reviewState=DRAFT`는 사람 글이 사용자 검수 전인 **초안**이라는 뜻이다. 화면은 초안임을 표시하고, 검수한 행만 `APPROVED`로 바꾼다. 이는 입력의 `deliveryState`와 별개다. `costSchema`의 `null`은 무료가 아니라 아직 확정 수치가 없다는 뜻이다. `PLANNED` 입력은 설명할 수 있지만 실행 가능하다고 표시하지 않는다. `firstStepsExplanation.state=UNMAPPED`는 K7 설명/바로가기 대응 확인 전, `NOT_APPLICABLE`은 근거 있는 해당 없음, `LINKED`는 글 단계 ID 연결을 뜻하며 어느 것도 달성 상태가 아니다.

## 도움말 읽기

| 경로 | 요청 | 200 응답 | 실패 |
| --- | --- | --- | --- |
| `GET /api/help/topics/{topicId}` | URL 인코딩된 주제 ID | `HelpTopicResponse` | 404 `HELP_TOPIC_NOT_FOUND` |
| `GET /api/help/topics` | 선택적으로 `If-None-Match` | `HelpTopicListResponse`; `INPUT`→`CONCEPT`→`TUTORIAL`, 각 종류 안에서 ID 오름차순. `ETag` 일치 시 304 | 월드 규칙 오류는 기존 도움말 API와 같음 |
| `GET /api/help/search?q={text}&limit={n}` | 공백 제거 검색어 2~80자, `limit` 기본 20·범위 1~50 | `HelpSearchResponse`; 제목→설명→예시 순, 동률은 ID 오름차순 | 400 `INVALID_SEARCH_QUERY` |
| `GET /api/help/context?inputId={inputId}` | 원장 입력 ID | `ContextHelpResponse` | 404 `INPUT_NOT_FOUND`, `HELP_TOPIC_NOT_FOUND` |
| `GET /api/help/failures/{reason}?inputId={inputId}` | 원장 실패 사유, 선택적 입력 ID | `FailureHelpResponse`; 입력 ID가 있으면 그 행에 선언된 사유여야 함 | 404 `FAILURE_REASON_NOT_FOUND`, `INPUT_NOT_FOUND`; 400 `REASON_NOT_FOR_INPUT` |

문맥 도움말의 `input`은 precheck나 실행 성공을 약속하지 않는다. 제출 전 실제 대상·권한·비용은 별도 precheck/preview 결과로 표시해야 한다. 관련 API가 없는 입력에 성공 미리보기를 만들지 않는다.

원장 실패 사유 중 `STATE_UNAVAILABLE`, `INVALID_INPUT`, `TARGET_UNAVAILABLE`처럼 여러 입력에서 서로 다른 조건을 가리키는 코드는 `inputId`가 주어지면 해당 입력의 설명·회복 조언을 우선한다. 공통 문구만으로 구체적인 원인을 알 수 없는 경우 새 조건을 추측하지 않고 실제 precheck/결과의 세부 메시지를 함께 표시한다.

입력 도움말 주제는 원장의 `helpTopicId`와 정확히 일치한다. 추가 글은 `data/help/topic-registry.json`에 `CONCEPT` 또는 `TUTORIAL` 종류로 먼저 등록하고, 각각 `concepts.<camelCase>` 또는 `tutorial.<camelCase>` ID만 쓴다. 저장소는 주제 파일과 등록부의 집합이 다르거나, 등록되지 않은 주제·중복 ID·깨진 관련 링크가 있으면 시작 시 거절한다. 목록의 `inputId`·`inputKind`는 입력 주제에서만 채우고, `excerpt`는 설명 첫 문단이다. 현재 등록부는 비어 있으며 기존 사람 글의 검수 상태를 바꾸지 않는다.

## 첫걸음 설명

설명 순서는 가입 → 장수 생성 → 첫 출사 → 첫 발령 → 첫 공사 → 첫 등용 → 첫 행군 → 첫 전투다. 각 단계는 `tutorial.signup`, `tutorial.createGeneral`, `tutorial.enlist`, `tutorial.dispatch`, `tutorial.work`, `tutorial.employ`, `tutorial.march`, `tutorial.battle` 주제 글과 K7이 검증한 실제 화면 바로가기 한 쌍이다. `GET /api/tutorial/progress`는 D21로 폐기된 계약이며 서버에 등록하지 않는다.

입력 원장 v5의 `firstStepsExplanationStepId`는 글 대응만 가리킨다. 현재 74행은 K7의 새 화면 대응 확인 전이라 모두 `UNMAPPED`다. 이전 K7 설계의 `action.enlist`, `court.dispatchReply`, `work.start`, `action.search`·`action.employ`, `action.deploy`·`action.move` 일곱 입력은 **후보**다. 글과 화면 바로가기가 확인되면 해당 단계 ID로 연결하고, 포함되지 않는 입력만 근거를 붙여 `N/A`로 분류한다. `TUTORIAL_READY`는 연결 글 승인과 화면 바로가기 증거 또는 검증된 해당 없음 증거 없이는 통과하지 못한다.

## 장수 생성 연계

장수 생성 **쓰기 API와 후보·선택 정책 읽기 API**는 서버 레인이 맡고, 가입·장수 생성 **화면**은 프론트 재구축 레인이 맡는다. 현재 `/api/join`과 `/api/select-pool`은 삼모 기반 계약이므로 이 문서가 그 API를 새 제품 규칙의 계약으로 인정하거나 재사용하지 않는다.

| 경로 | 요청 | 응답 | 실패 |
| --- | --- | --- | --- |
| `POST /api/generals/creation` | 인증 계정, `CreateGeneralRequest` | 202 `CreateGeneralAccepted`; 접수일 뿐 생성 성공이 아님 | 400 `INVALID_REQUEST`, 401 `AUTH_REQUIRED`, 409 `WORLD_CHANGED`·`GENERAL_ALREADY_OWNED`·`REQUEST_ID_REUSED`·`NAME_ALREADY_USED`, 422 `INVALID_NAME`·`INVALID_NATIVE_COUNTY`·`INVALID_STATS`·`INVALID_IDEOLOGY`·`INVALID_TRAIT`·`HISTORICAL_PERSON_NOT_APPEARED`·`HISTORICAL_PERSON_UNAVAILABLE`, 503 `CREATION_POLICY_UNAVAILABLE`(선택 정책 원장 로드 실패) |
| `GET /api/generals/creation/{requestId}` | 현재 라우팅된 월드에서 인증 계정이 제출한 ID만 허용 | 200 `CreateGeneralResult` | 401 `AUTH_REQUIRED`; 타인 ID·다른 월드 ID·없는 ID는 동일한 404 `CREATION_REQUEST_NOT_FOUND` |

`clientRequestId`는 호출자가 만든 안정된 UUID 문자열이다. 서버는 별도 UUID를 발급하지 않고 제출된 `clientRequestId`를 응답과 GET 경로의 `requestId`로 그대로 쓴다. 요청 기록의 유일 키는 `(accountId, worldId, clientRequestId)`이며 다른 계정이나 월드는 같은 UUID를 독립적으로 사용할 수 있다. 같은 계정·월드·ID·동일 본문 재시도는 새 생성 작업을 만들지 않고 같은 `requestId`의 202 접수 응답을 반환한다. 같은 키에 다른 본문을 제출하면 409 `REQUEST_ID_REUSED`다. 서버는 라우팅된 월드를 선택하고 `expectedWorldId`가 그 월드와 다르면 409 `WORLD_CHANGED`로 거절한다. 요청으로 임의의 월드를 바꾸지 못한다. 생성 성공은 데몬이 영속 flush를 끝낸 뒤 `CREATED` 결과와 본인 장수 소유가 함께 확인될 때만 표시한다. `PENDING`과 `REJECTED`를 성공으로 표시하지 않는다. 접수 전 검사 실패는 표의 동기 4xx로, 접수 뒤 쓰기 경로의 점유 경쟁·유효성 오류는 같은 오류 코드를 `REJECTED.error`로 반환한다. 클라이언트는 두 경로를 모두 처리한다.

`CUSTOM`은 플레이어가 이름·본관 縣·통솔·무력·지력·정치·매력·주의(主義)·개성(個性)을 직접 고른다(2026-09-27 사용자 결정). 다섯 능력치는 각각 **정수 20–85**, 합계는 **정확히 300**이다. 주의·개성의 `ideologyId`·`traitId`는 아래 안정 코드로 저장하며 표시명과 분리한다. 선택 정책 원장이 코드와 표시명을 함께 관리하고, 서버 레인 소유의 후보 읽기 API가 둘을 내려준다. 표시명을 바꿔도 저장 ID는 바꾸지 않는다.

**이름 규칙 확정(2026-10-01 사용자 결정, K0 경유 계약판 11:11 인계):** 서버가 입력을 NFC로 정규화하고 양끝 공백을 제거한 뒤 Unicode code point **1–12개**인지 검사한다. 각 글자는 한글·한자·라틴 문자만 허용하고, 글자 **사이**에만 ASCII 빈칸 한 개 또는 가운뎃점 `·` 한 개를 허용한다. 혼합 스크립트는 금지하지 않는다. 숫자·다른 기호·제어문자와 남은 결합 문자는 거절한다. 같은 월드의 기존 장수와 NFC·대소문자 무시 키가 같으면 `NAME_ALREADY_USED`다. 시나리오 NPC 표시 표지는 비교 전에 제거하지만 기존 이름 행을 자동 정규화·backfill하지 않는다. `INVALID_NAME`은 422와 쉬운 설명, 중복은 409와 다른 이름을 고르라는 설명을 반환한다. 이 규칙은 `GET /api/generals/creation/options.nameRule`, POST 사전 검사, 데몬의 flush 직전 검사가 같은 정본을 사용한다. V74의 `creationNameKeyV1` 부분 unique 인덱스는 새 CUSTOM 행의 월드별 동시 커밋 중복을 차단하고 기존 이름과의 충돌은 데몬의 현재 월드 검사로 차단한다.

| 주의 ID | 표시명 | 개성 ID | 표시명 |
| --- | --- | --- | --- |
| `WANGDO` | 왕도 | `DISCIPLINE` | 규율 |
| `PAEDO` | 패도 | `WATER_COMBAT` | 수전 |
| `ADO` | 아도 | `RENOWN` | 명성 |
| `HALGEO` | 할거 | `DEBATER` | 논객 |
| `MYEONGRI` | 명리 | `STRATEGIST` | 책사 |
| `YEGYO` | 예교 | `SINGLE_RIDER` | 일기 |

이 이름들은 [Koei 공식 매뉴얼의 주의](https://www.gamecity.ne.jp/manual/sangokushi14-pk/ce/jp/3100.html), [공식 개성 예시](https://www.gamecity.ne.jp/sangokushi14/chara-personality.html), [외교·계략 예시](https://www.gamecity.ne.jp/sangokushi14/system-strategy.html), [전투 예시](https://www.gamecity.ne.jp/sangokushi14/system-battle.html)에서 확인했다. 서버는 본관 縣, 능력치, 두 선택 목록을 쓰기 경로에서 재검증한다. 주의·개성은 효과 설계와 검증 전까지 **표시용 태그로만 저장**한다. 원작의 효과·상성 수치나 숨은 보너스를 부의 규칙으로 복사하지 않는다. 공식 자료에서 별도 창작 상성 숫자 입력은 확인하지 못해 이 요청에도 넣지 않는다. 선택 조합 금지는 없다. 정책 원장을 로드하지 못하면 503으로 닫는다. 삼모 `PageJoin`의 합계·범위·무작위 규칙을 가져오지 않는다. `HISTORICAL`은 시나리오 시점에 이미 등장한 기존 인물의 **현재 월드 `general.id`**를 제출한다. 서버는 등장 여부, 현 시점의 점유·소속·생존·선택 가능성, 현재 위치·배치와 서버당 단 한 장 제약을 **같은 쓰기 경로에서** 다시 확인한다. 본관 근거가 없는 기존 인물은 본관을 `null`로 보존하며, 현재 위치를 본관으로 복사하거나 본관 결손만으로 선택 불가 판정하지 않는다. 역사 인물의 기존 능력·성향·개성·명망 값은 결손까지 그대로 보존하고 다시 추첨하지 않는다. 후보 읽기 화면은 등장 인물 전체를 보여 줄 수 있으나 점유된 인물은 선택 불가로 표시한다.

각 계정은 해당 서버·월드에 사람 장수 한 명만 소유할 수 있다. 두 동시 요청도 한 장만 성공하고 나머지는 `GENERAL_ALREADY_OWNED`로 끝나야 한다. 역사 인물은 기존 한 장을 소유로 전환하며 복제하지 않는다. 두 계정의 동시 선택도 한 쪽만 성공한다. 첫걸음의 장수 생성 설명은 본 서버 T3 결과를 가리킨다. 사람에게 보이는 도움말 글은 이 레인이 초안을 쓰고 사용자가 검수한다.

## 예시와 게이트

fixture는 `docs/development/fixtures/help-tutorial/`에 있다. `general-creation-historical-request.json`은 역사 인물 요청, `general-creation-custom-request.json`은 승인된 선택값을 담은 요청 예시다. `general-creation-policy-unavailable.json`은 선택 정책 원장 로드 실패의 응답 예시다. `general-creation-accepted.json`은 접수, `general-creation-created.json`과 `general-creation-rejected.json`은 서로 다른 최종 결과 예시다. 내용은 계약 예시이며 실제 제공 증거가 아니다. E8은 모든 `helpTopicId`의 실체·고아 없음·원장 수치 중복 없음·모든 실패 사유의 설명을 적색 프로브로 검증한다. E9 첫걸음 진척·달성 판정은 D21로 폐기됐다. E10은 승인된 첫걸음 설명 글·실제 화면 바로가기 또는 근거 있는 해당 없음과 단계별 증거 없이는 `TUTORIAL_READY`를 거절한다. 실제 UI 성공·실패 경로와 문서는 해당 화면/입력 소유 PR에서 검증한다.
