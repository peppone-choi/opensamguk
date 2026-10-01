# 도움말·튜토리얼 서버 API 계약

상위 목표: [공개 튜토리얼 흐름](../superpowers/specs/2026-09-17-general-and-retinue-campaign-redesign.md) §15.2, [도움말 완료 조건](../superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md) §8. 서버 구현 추적: OPENSAM-293~295 / #961~963. 이 문서는 **계약**이며 엔드포인트가 이미 제공된다는 뜻이 아니다.

## 공통 규칙

- 경로는 game-api의 `/api` 아래다. JSON 필드는 camelCase, 문자 인코딩은 UTF-8이다. 화면은 기존 gateway 프록시와 서버 선택 규약을 따른다. `/hwiha`는 ADR-LITE-066의 리다이렉트 전용 경로다.
- `ruleProfile=HWIHA` 월드에서만 제공한다. 다른 규칙 월드는 404 `WORLD_PROFILE_UNAVAILABLE`, 활성 월드가 없으면 503 `WORLD_UNAVAILABLE`이다.
- 성공 응답의 `schemaVersion`은 정수 `1`이다. 오류 응답은 `{ "error": { "code": string, "message": string } }`이다. 미확인 주제·입력·사유는 404, 잘못된 검색 질의는 400이다.
- `inputId`, `helpTopicId`, `failureReason`, `objectiveId`는 식별자다. 화면은 반환된 제목·설명·회복 조언을 표시한다. 비용·권한·대상·시기는 현재 `InputCatalog` 원장 행에서 투영하며 사람 글에 복제하지 않는다.
- 정적 도움말 읽기는 로그인 없이 가능하다. 개인 튜토리얼 진척은 인증 필수다. 본인 소유 장수의 진척만 제공하고, 조정·국가 사건을 자기 사건처럼 제시하지 않는다.
- `GET`은 상태를 바꾸지 않는다. 목표 달성은 확인된 사건의 원자적 쓰기 경로에서만 판정하며 동일 사건 재처리로 중복 완료되지 않는다.

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
  helpTopicId: string; tutorialObjectiveId: string | null;
};
type ContextHelpResponse = { schemaVersion: 1; topic: HelpTopic; input: InputContract };
type FailureHelpResponse = {
  schemaVersion: 1; reason: string; reviewState: 'DRAFT' | 'APPROVED'; explanation: string;
  recoveryAdvice: string; relatedTopicIds: string[];
};
type ObjectiveProgress = {
  id: string; title: string; order: number; scope: 'ACCOUNT' | 'GENERAL';
  prerequisites: string[]; status: 'LOCKED' | 'CURRENT' | 'COMPLETED';
  completedAt: string | null; helpTopicId: string | null;
};
type TutorialProgressResponse = {
  schemaVersion: 1; worldId: number; accountId: string;
  generalId: number | null; objectives: ObjectiveProgress[];
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

`reviewState=DRAFT`는 사람 글이 사용자 검수 전인 **초안**이라는 뜻이다. 화면은 초안임을 표시하고, 검수한 행만 `APPROVED`로 바꾼다. 이는 입력의 `deliveryState`와 별개다. `costSchema`의 `null`은 무료가 아니라 아직 확정 수치가 없다는 뜻이다. `PLANNED` 입력은 설명할 수 있지만 실행 가능하다고 표시하지 않는다. `tutorialObjectiveId=null`은 원장에 사유가 기록된 N/A만 투영한다. 미기록 N/A는 게이트 실패다.

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

2026-09-30 승인된 명령 이름 중 도움말 입력 주제는 `action.tradeGrain`/`commands.action.tradeGrain` → 「쌀 사고팔기」, `action.convertProficiency`/`commands.action.convertProficiency` → 「병종 바꿔 익히기」로 연결한다. `inputId`·`helpTopicId`는 그대로다. 「군량 습격→보급 습격」은 현재 제품 입력·서버 카드에 없는 **미구현 초안 카드의 이름 승인**이다(`2026-09-17-stratagem-card-catalog-draft.md:23`, 이미지 카드 설계 `nameKo`). 기존 `stratagem.raid`와 다른 카드이고 실제 입력 키가 없어 이 도움말 원장에 대응 주제를 만들거나 기존 `stratagem.raid` 주제를 바꾸지 않는다. 초안 목록·이미지 이름 변경은 카드 실제 입력 등록과 함께 처리한다. 입력 카탈로그의 실재 두 `displayName`은 C1 소유 파일에서 별도로 일치시킨다. 이름 승인은 나머지 도움말 사람 글의 `APPROVED`나 `HELP_READY` 승격을 뜻하지 않는다.

## 튜토리얼 읽기

| 경로 | 요청 | 200 응답 | 실패 |
| --- | --- | --- | --- |
| `GET /api/tutorial/progress` | 인증 주체에서 계정 식별; query/body로 타인 ID를 받지 않음 | `TutorialProgressResponse` | 401 `AUTH_REQUIRED` |

튜토리얼은 본 서버 월드와 분리된 **가속 튜토리얼 월드**에서 진행한다. `TutorialProgressResponse.worldId`는 이 응답이 가리키는 튜토리얼 월드의 ID이며 본 서버 월드 ID가 아니다. `generalId`는 그 튜토리얼 월드에서 인증 계정이 소유한 장수의 ID이고, 장수 생성 전에는 `null`이다. 본 서버 월드의 장수와 사건은 이 진척에 섞지 않는다.

첫걸음 진척 칩은 **연습 서버에서만** 보여준다. 응답의 `worldId`는 그 연습 서버의 실제 월드 ID로 고정하고, 본 서버의 사건·장수·진척을 대신 사용하지 않는다. 연습 월드를 계정별로 둘지 공유할지는 U6 미결정이며, 이 계약은 어느 격리 방식을 확정하지 않는다.

목표 순서는 가입 → 장수 생성 → 첫 출사 → 첫 발령 → 첫 공사 → 첫 등용 → 첫 행군 → 첫 전투다. 첫 두 단계는 인증 계정 범위(장수 생성은 해당 연습 월드에서 생성한 본인 장수), 뒤 여섯 단계는 이 응답의 `worldId`에 속한 본인 소유 장수 범위다. 달성 판정은 제출·예약·HTTP 접수 시점이 아니라 아래 확정 증거에 따른다.

| 목표 | 달성 증거 | 접수만으로 완료하지 않는 경우 |
| --- | --- | --- |
| 가입 | gateway의 해당 계정 생성 확정 | 가입 시도·예약 |
| 장수 생성 | 해당 계정·연습 월드의 장수 생성 완료와 소유 확인 | 생성 요청 202·`PENDING`·`REJECTED` |
| 첫 출사 | 본인 장수의 출사 결과 확정 | 제출·예약·실패 결과 |
| 첫 발령 | 본인 장수의 발령 **수락 확정** | 발령 발행·대기·거절·취소 |
| 첫 공사 | 본인 장수에 귀속되는 공사 **착수 확정** | 공사 예약·조건 거절 |
| 첫 등용 | 본인 장수의 등용 **판정 결과 수신**; 성공과 저항 모두 포함 | 제출·예약·결과 미수신 |
| 첫 행군 | 본인 장수의 **출병 또는 이동** 결과 확정 | 경로 선택·제출·실패 결과 |
| 첫 전투 | 본인 장수의 **전투 결과 수신** 확정; 승패 무관 | 전투 시작·관전·결과 미수신 |

K7-09의 가입·장수 생성·공사 착수·전투 결과 수신 **네 증거 종류는 아직 실제 생산 경로 검증 전**이다. 가입은 gateway 계정 생성 원천에서 가져오며 장수 생성 전의 `game_event`에 끼워 넣지 않는다. 다른 목표도 서버의 실제 결과·소유권 근거를 확인한 뒤 연결한다. 진척 쓰기는 `(accountId, worldId)` 경계를 지키고 동일 근거 사건의 재처리로 중복 완료나 완료 시각 변경을 만들지 않는다. 실패·예약·다른 계정 또는 월드의 사건은 완료 근거가 아니다. E9에서 네 증거의 생산·소유권·중복·콜드 재로드와 각 목표의 적색 사례를 검증한다.

## 장수 생성 연계

장수 생성 **쓰기 API**는 E8–E10 도움말·튜토리얼 레인이 맡는다. 가입·장수 생성 **화면**과 화면 전용 후보 읽기 API는 프론트 재구축 레인이 맡는다. 현재 `/api/join`과 `/api/select-pool`은 삼모 기반 계약이므로 이 문서가 그 API를 새 제품 규칙의 계약으로 인정하거나 재사용하지 않는다.

| 경로 | 요청 | 응답 | 실패 |
| --- | --- | --- | --- |
| `POST /api/generals/creation` | 인증 계정, `CreateGeneralRequest` | 202 `CreateGeneralAccepted`; 접수일 뿐 생성 성공이 아님 | 400 `INVALID_REQUEST`, 401 `AUTH_REQUIRED`, 409 `WORLD_CHANGED`·`GENERAL_ALREADY_OWNED`·`REQUEST_ID_REUSED`, 422 `INVALID_NATIVE_COUNTY`·`INVALID_STATS`·`INVALID_IDEOLOGY`·`INVALID_TRAIT`·`HISTORICAL_PERSON_NOT_APPEARED`·`HISTORICAL_PERSON_UNAVAILABLE`, 503 `CREATION_POLICY_UNAVAILABLE`(선택 정책 원장 로드 실패) |
| `GET /api/generals/creation/{requestId}` | 현재 라우팅된 월드에서 인증 계정이 제출한 ID만 허용 | 200 `CreateGeneralResult` | 401 `AUTH_REQUIRED`; 타인 ID·다른 월드 ID·없는 ID는 동일한 404 `CREATION_REQUEST_NOT_FOUND` |

`clientRequestId`는 호출자가 만든 안정된 UUID 문자열이다. 서버는 별도 UUID를 발급하지 않고 제출된 `clientRequestId`를 응답과 GET 경로의 `requestId`로 그대로 쓴다. 요청 기록의 유일 키는 `(accountId, worldId, clientRequestId)`이며 다른 계정이나 월드는 같은 UUID를 독립적으로 사용할 수 있다. 같은 계정·월드·ID·동일 본문 재시도는 새 생성 작업을 만들지 않고 같은 `requestId`의 202 접수 응답을 반환한다. 같은 키에 다른 본문을 제출하면 409 `REQUEST_ID_REUSED`다. 서버는 라우팅된 월드를 선택하고 `expectedWorldId`가 그 월드와 다르면 409 `WORLD_CHANGED`로 거절한다. 요청으로 임의의 월드를 바꾸지 못한다. 생성 성공은 데몬이 영속 flush를 끝낸 뒤 `CREATED` 결과와 본인 장수 소유가 함께 확인될 때만 표시한다. `PENDING`과 `REJECTED`를 성공으로 표시하지 않는다. 접수 전 검사 실패는 표의 동기 4xx로, 접수 뒤 쓰기 경로의 점유 경쟁·유효성 오류는 같은 오류 코드를 `REJECTED.error`로 반환한다. 클라이언트는 두 경로를 모두 처리한다.

`CUSTOM`은 플레이어가 이름·본관 縣·통솔·무력·지력·정치·매력·주의(主義)·개성(個性)을 직접 고른다(2026-09-27 사용자 결정). 다섯 능력치는 각각 **정수 20–85**, 합계는 **정확히 300**이다. 주의·개성의 `ideologyId`·`traitId`는 아래 안정 코드로 저장하며 표시명과 분리한다. 선택 정책 원장이 코드와 표시명을 함께 관리하고, 프론트 레인 소유의 후보 읽기 API가 둘을 내려준다. 표시명을 바꿔도 저장 ID는 바꾸지 않는다.

| 주의 ID | 표시명 | 개성 ID | 표시명 |
| --- | --- | --- | --- |
| `WANGDO` | 왕도 | `DISCIPLINE` | 규율 |
| `PAEDO` | 패도 | `WATER_COMBAT` | 수전 |
| `ADO` | 아도 | `RENOWN` | 명성 |
| `HALGEO` | 할거 | `DEBATER` | 논객 |
| `MYEONGRI` | 명리 | `STRATEGIST` | 책사 |
| `YEGYO` | 예교 | `SINGLE_RIDER` | 일기 |

설계 화면에서는 `RENOWN`을 다른 명성·명망 수치와 구분해 **「개성: 명성」** 범주로 표시한다. 선택 원장의 `displayNameKo: 명성`, `RENOWN` 저장 키와 효과 없음 상태는 유지한다. 이 범주 표시는 개성 효과 승인이나 도움말 사람 글 승인으로 해석하지 않는다.

이 이름들은 [Koei 공식 매뉴얼의 주의](https://www.gamecity.ne.jp/manual/sangokushi14-pk/ce/jp/3100.html), [공식 개성 예시](https://www.gamecity.ne.jp/sangokushi14/chara-personality.html), [외교·계략 예시](https://www.gamecity.ne.jp/sangokushi14/system-strategy.html), [전투 예시](https://www.gamecity.ne.jp/sangokushi14/system-battle.html)에서 확인했다. 서버는 본관 縣, 능력치, 두 선택 목록을 쓰기 경로에서 재검증한다. 주의·개성은 효과 설계와 검증 전까지 **표시용 태그로만 저장**한다. 원작의 효과·상성 수치나 숨은 보너스를 부의 규칙으로 복사하지 않는다. 공식 자료에서 별도 창작 상성 숫자 입력은 확인하지 못해 이 요청에도 넣지 않는다. 선택 조합 금지는 없다. 정책 원장을 로드하지 못하면 503으로 닫는다. 삼모 `PageJoin`의 합계·범위·무작위 규칙을 가져오지 않는다. `HISTORICAL`은 시나리오 시점에 이미 등장한 기존 인물 ID를 제출한다. 서버는 190 시드의 등장 여부, 현 시점의 점유·소속·생존·선택 가능성, 본관 배치와 서버당 단 한 장 제약을 **같은 쓰기 경로에서** 다시 확인한다. 역사 인물의 기존 능력·성향·개성·명망 값은 결손까지 그대로 보존하고 다시 추첨하지 않는다. 후보 읽기 화면은 등장 인물 전체를 보여 줄 수 있으나 점유된 인물은 선택 불가로 표시한다.

각 계정은 해당 서버·월드에 사람 장수 한 명만 소유할 수 있다. 두 동시 요청도 한 장만 성공하고 나머지는 `GENERAL_ALREADY_OWNED`로 끝나야 한다. 역사 인물은 기존 한 장을 소유로 전환하며 복제하지 않는다. 두 계정의 동시 선택도 한 쪽만 성공한다. 튜토리얼 월드의 생성·점유는 본 서버 월드로 전파하지 않는다. 사람에게 보이는 도움말 글은 이 레인이 초안을 쓰고 사용자가 검수한다.

## 예시와 게이트

fixture는 `docs/development/fixtures/help-tutorial/`에 있다. `general-creation-historical-request.json`은 역사 인물 요청, `general-creation-custom-request.json`은 승인된 선택값을 담은 요청 예시다. `general-creation-policy-unavailable.json`은 선택 정책 원장 로드 실패의 응답 예시다. `general-creation-accepted.json`은 접수, `general-creation-created.json`과 `general-creation-rejected.json`은 서로 다른 최종 결과 예시다. 내용은 계약 예시이며 실제 제공·진척 증거가 아니다. E8은 모든 `helpTopicId`의 실체·고아 없음·원장 수치 중복 없음·모든 실패 사유의 설명을 적색 프로브로 검증한다. E9는 실제 사건 발화, 소유권, 사건 중복, flush 뒤 콜드 재로드를 검증한다. E10은 목표 또는 사유 있는 N/A 및 단계별 증거 없이는 승격을 거절한다. 실제 UI 성공·실패 경로와 문서는 해당 화면/입력 소유 PR에서 검증한다.
