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
  id: string; title: string; sections: HelpSection;
  sources: HistoricalSource[]; relatedTopicIds: string[];
};
type HelpTopicResponse = { schemaVersion: 1; topic: HelpTopic };
type HelpSearchHit = { id: string; title: string; excerpt: string; matchedSection: string };
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
  schemaVersion: 1; reason: string; explanation: string;
  recoveryAdvice: string; relatedTopicIds: string[];
};
type ObjectiveProgress = {
  id: string; title: string; order: number; scope: 'ACCOUNT' | 'GENERAL';
  prerequisites: string[]; status: 'LOCKED' | 'CURRENT' | 'COMPLETED';
  completedAt: string | null; helpTopicId: string | null;
};
type TutorialProgressResponse = {
  schemaVersion: 1; worldId: string; accountId: string;
  generalId: number | null; objectives: ObjectiveProgress[];
};
```

`costSchema`의 `null`은 무료가 아니라 아직 확정 수치가 없다는 뜻이다. `PLANNED` 입력은 설명할 수 있지만 실행 가능하다고 표시하지 않는다. `tutorialObjectiveId=null`은 원장에 사유가 기록된 N/A만 투영한다. 미기록 N/A는 게이트 실패다.

## 도움말 읽기

| 경로 | 요청 | 200 응답 | 실패 |
| --- | --- | --- | --- |
| `GET /api/help/topics/{topicId}` | URL 인코딩된 주제 ID | `HelpTopicResponse` | 404 `HELP_TOPIC_NOT_FOUND` |
| `GET /api/help/search?q={text}&limit={n}` | 공백 제거 검색어 2~80자, `limit` 기본 20·범위 1~50 | `HelpSearchResponse`; 제목→설명→예시 순, 동률은 ID 오름차순 | 400 `INVALID_SEARCH_QUERY` |
| `GET /api/help/context?inputId={inputId}` | 원장 입력 ID | `ContextHelpResponse` | 404 `INPUT_NOT_FOUND`, `HELP_TOPIC_NOT_FOUND` |
| `GET /api/help/failures/{reason}?inputId={inputId}` | 원장 실패 사유, 선택적 입력 ID | `FailureHelpResponse`; 입력 ID가 있으면 그 행에 선언된 사유여야 함 | 404 `FAILURE_REASON_NOT_FOUND`, `INPUT_NOT_FOUND`; 400 `REASON_NOT_FOR_INPUT` |

문맥 도움말의 `input`은 precheck나 실행 성공을 약속하지 않는다. 제출 전 실제 대상·권한·비용은 별도 precheck/preview 결과로 표시해야 한다. 관련 API가 없는 입력에 성공 미리보기를 만들지 않는다.

실패 사유 133종 중 `STATE_UNAVAILABLE`, `INVALID_INPUT`, `TARGET_UNAVAILABLE`처럼 여러 입력에서 서로 다른 조건을 가리키는 코드는 `inputId`가 주어지면 해당 입력의 설명·회복 조언을 우선한다. 공통 문구만으로 구체적인 원인을 알 수 없는 경우 새 조건을 추측하지 않고 실제 precheck/결과의 세부 메시지를 함께 표시한다.

## 튜토리얼 읽기

| 경로 | 요청 | 200 응답 | 실패 |
| --- | --- | --- | --- |
| `GET /api/tutorial/progress` | 인증 주체에서 계정 식별; query/body로 타인 ID를 받지 않음 | `TutorialProgressResponse` | 401 `AUTH_REQUIRED`, 403 `GENERAL_NOT_OWNED` |

튜토리얼은 본 서버 월드와 분리된 **가속 튜토리얼 월드**에서 진행한다. `TutorialProgressResponse.worldId`는 이 응답이 가리키는 튜토리얼 월드의 ID이며 본 서버 월드 ID가 아니다. `generalId`는 그 튜토리얼 월드에서 인증 계정이 소유한 장수의 ID이고, 장수 생성 전에는 `null`이다. 본 서버 월드의 장수와 사건은 이 진척에 섞지 않는다.

목표 순서는 가입 → 장수 생성 → 첫 출사 → 첫 발령 → 첫 공사 → 첫 등용 → 첫 행군 → 첫 전투다. 첫 두 단계는 인증 계정 범위(장수 생성은 튜토리얼 월드에서 생성한 장수), 뒤 여섯 단계는 위 `worldId`의 본인 소유 장수 범위다. 가입 증거는 gateway 계정 생성 원천에서 가져오며 장수 생성 전의 `game_event`에 끼워 넣지 않는다. 첫 전투는 승패와 관계없이 해당 장수의 **전투 결과 수신** 사건이 확정될 때 달성한다. E9에서 이 사건의 실제 생산·소유권·중복 처리를 검증한다.

## 장수 생성 연계

장수 생성 **쓰기 API**는 E8–E10 도움말·튜토리얼 레인이 맡는다. 가입·장수 생성 **화면**과 화면 전용 읽기 API는 프론트 재구축 레인이 맡는다. 현재 `/api/join`과 `/api/select-pool`은 삼모 기반 계약이므로 이 문서가 그 API를 새 제품 규칙의 계약으로 인정하거나 재사용하지 않는다. 이 레인의 별도 계약 PR에 창작 장수 본관 縣, 역사 인물 선택, 서버당 한 장 제약, 오류와 fixture를 추가한다. 사람에게 보이는 도움말 글은 이 레인이 초안을 쓰고 사용자가 검수한다.

## 예시와 게이트

fixture는 `docs/development/fixtures/help-tutorial/`에 있다. 내용은 계약 예시이며 실제 제공·진척 증거가 아니다. E8은 모든 `helpTopicId`의 실체·고아 없음·원장 수치 중복 없음·모든 실패 사유의 설명을 적색 프로브로 검증한다. E9는 실제 사건 발화, 소유권, 사건 중복, flush 뒤 콜드 재로드를 검증한다. E10은 목표 또는 사유 있는 N/A 및 단계별 증거 없이는 승격을 거절한다. 실제 UI 성공·실패 경로와 문서는 해당 화면/입력 소유 PR에서 검증한다.
