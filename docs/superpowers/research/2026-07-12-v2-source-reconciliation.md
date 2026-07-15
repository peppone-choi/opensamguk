# 오픈삼국 v2 자료 대조 및 결정 기록

> 작성일: 2026-07-12
> 상태: adopted-for-planning
> 범위: 저장소 문서, `docs/wiki`, myosam 도움말 원문, 현재 v1 운영 구조

## 조사 범위

- 저장소 운영·패러티 정본: `AGENTS.md`, `CLAUDE.md`, `docs/superpowers/WORKING_SYSTEM.md`, `docs/superpowers/LOOP_ENGINEERING.md`.
- v1 계획·게이트·루프: `docs/superpowers/plans/`, `docs/superpowers/specs/`, `docs/loops/` 전체 인벤토리.
- 위키: `docs/wiki` 106개 문서, 약 1,239개 섹션.
- 외부 도움말: [묘섭 도움말 목차](http://www.myosam.com/dokuwiki/doku.php?id=help:start), 현재 페이지 제목과 목차 버전 `1.0.6` 확인. 로컬 원문 미러는 `docs/wiki/raw/myosam-help/`에 보존.
- v2 제안 문서: `docs/wiki/raw/opensamguk-v2-downloads/{PRD,ROADMAP,CLAUDE,CLAUDE_CODE_KICKOFF}.md`, 기존 v2 게임 기획·출시 계획.

## 자료별 채택 범위

| 자료 | 채택 | 보류·거부 |
|---|---|---|
| PHP `legacy/devsam-core` | v1 동작·수치·로그·부수효과의 유일한 패러티 기준 | v2 신규 시스템의 규칙을 PHP에 억지로 끼워 맞추지 않음 |
| myosam 도움말 1.0.6 | 장수/세력/도시/턴 입력/예약표/지도/외교/전쟁의 사용자 문법과 온보딩 구조 | 문구·화면·자산의 복제, 도움말의 설명을 곧바로 수치 정본으로 사용 |
| v2 PRD Rev 2 | 자원 계층, 부하 2트랙, 건설물, deterministic runtime, 모바일 우선 목표 | 112개 커맨드 전체 구현을 MVP 선행 조건으로 삼지 않음 |
| 기존 v2 게임 기획 | 동적 전쟁, replay-first, 가신 제안/편견, 어전회의, 도독부/봉토/봉신 계약 | 모든 기능을 한 번에 구현하는 로드맵, 단순 버프형 가신 |
| 기존 v2 출시 계획 | 조작 대상 → 부곡 → 작전 → replay → 가신 → 회의 → 봉건제 순서 | 새 모노레포·새 스택으로 v1을 교체하는 재작성 |
| 현재 v1 운영 구조 | Kotlin/Spring, Next, PostgreSQL, Redis Streams/pub-sub, `InMemoryTurnWorld`와 JDBC flush 재사용 | v1 게임 데이터에 v2 테이블을 즉석으로 섞거나 production s1을 실험 월드로 사용 |

## 충돌 해소

### 시간

기존 문서의 `1시간=1턴`, `1분 루프`, 현재 s1의 `tick_seconds=60`은 서로 다른 층의 값으로 분리한다.

- 내부 simulation tick: v2 설계 목표로 200ms를 허용하되, 비즈니스 이벤트를 매 200ms마다 DB에 쓰지 않는다.
- 장수 command cadence: 월드 설정값. production 기본은 60분 프로파일, QA/s1 검증은 1분 프로파일.
- 게임 날짜: 상순/중순/하순 표현을 유지하되, 월간 경제 이벤트와 순 단위 이벤트를 별도 trigger로 둔다.
- UI 갱신: 턴마다 전체 페이지를 새로고침하지 않고, `commandResolved`, `turnCompleted`, `battlePhaseChanged` 같은 대상 이벤트만 해당 query를 무효화한다.

### 지도

2026-07-14 실플레이 역기획과 사용자 결정에 따라 3D를 v2의 기본 지도 surface로 확정한다. 전략 지도와 전술 전장은 같은 world coordinate·selection·terrain version 계약을 사용한다. MVP는 도시 3개, route 2개, terrain patch 1개, formation 4개의 제한된 3D 수직 슬라이스로 시작하며, 전체 대륙 지형을 선행 조건으로 삼지는 않는다.

2D는 독립된 기본 제품 모드가 아니다. WebGL 불가 환경과 접근성을 위한 정보 fallback 또는 정사영 지휘 표현으로만 유지한다. 카메라·asset·LOD는 presentation layer이고, 이동·충돌·피해·시야 판정은 서버의 연속 좌표 simulation이 소유한다. 근거와 관찰 범위는 `docs/superpowers/research/2026-07-14-samnet-live-play-reverse-design.md`에 고정한다.

### 전투 레퍼런스 대조

- 코삭 2 자료에서 확인한 핵심은 실시간 대형 부대, 선형·종대·방진, 사기·피로·경험, 장교·기수·드럼/신호, 도로·지형·보급, 포병·기병과 대형 붕괴다. 참고: [GameSpot 핸즈온](https://www.gamespot.com/articles/cossacks-ii-napoleonic-wars-hands-on/1100-6118262/), [GameSpot 리뷰](https://www.gamespot.com/reviews/cossacks-ii-napoleonic-wars-review/1900-6123036/).
- 토탈워: 삼국 공식 자료에서 확인한 핵심은 최대 3명의 장수와 장수별 retinue, 장수 역할에 따른 병종·전투 영향, 캐릭터 관계·만족도·모집·진급을 캠페인과 전투에 연결하는 구조다. 참고: [Total War Academy](https://academy.totalwar.com/games/total-war-three-kingdoms/), [공식 매뉴얼](https://www.feralinteractive.com/en/manuals/threekingdomstw/1.0/steam/?access=zooevrj6xb).
- 사용자가 지정한 [토탈 워: 삼국 나무위키 5절](https://namu.wiki/w/%ED%86%A0%ED%83%88%20%EC%9B%8C:%20%EC%82%BC%EA%B5%AD#s-5)도 대조했다. 해당 설명은 군단을 최대 3명의 영웅 인물과 인물별 최대 6개 부대로 구성하고, 연구로 상위 병종을 해금하며 인물별 특수 모집 풀을 두는 구조를 설명한다. 이 자료는 역사적 패리티가 아니라 시스템 참고 자료로만 사용한다.
- 따라서 v2 전투는 타일 전투가 아니라 연속 좌표 fixed-tick 대형 부대 전투로 정정한다. 공통 기반에는 formation footprint, facing, command radius, morale/fatigue, retinue, replay를 포함하고, 플레이어 전장은 테이블탑 미니어처처럼 읽히게 한다.

### 실행 모델

현재 v1의 즉시 명령은 `Redis Streams → engine handler → ChangeRecorder → JDBC flush → commandResult` 순서로 확정한다. `commandResult`는 flush 이후에만 발행한다. 읽기 API의 `front-info` polling은 장수 생성 완료 신호가 아니라 보조 read model 확인용으로만 남긴다.

레거시 `TurnExecutionHelper.php:259-347`에서는 한 장수의 due 시점에 `nation_turn` 사령 명령을 먼저 처리하고 `general_turn` 개인 명령을 다음에 처리한 뒤 각각 `pullNationCommand`/`pullGeneralCommand`를 실행한다. v2에서도 이 링과 순서는 보존한다. 개인턴의 `che_출병`이 침공 `Operation`을 만들고, 실제 교전 조건이 충족된 뒤 별도 전술 세션이 열린다. 사령턴은 열린 전선의 보급·원군·방어·퇴각 정책만 조정하고, v2 전술 명령은 두 링을 대체하지 않는 battle order stream을 사용한다.

### 범위

v2의 첫 출시 단위는 112개 커맨드가 아니라 하나의 완결된 장면이다.

`3D 지도에서 작전 경로 선택 → 작전 승인 → 원군 지연 → 접근/정찰/요격/전투/전후 처리 → 3D replay 조회 → 가신 신뢰·봉신 충성·도시 민심 변화`

이 장면이 deterministic하고 운영 가능한 뒤에 커맨드와 건물·연구·황제제를 확장한다.

## 확인된 v1 착수 위험

- v1 기준 문서는 장기 `LongSimReplayGateTest` disabled를 아직 차단 항목으로 기록한다.
- v1은 실제 s1에서 턴·출병·AI·flush를 확인했지만, 현재 s1에는 사용자 계정/소유 장수가 없어 사용자 장수 생성의 실계정 smoke는 별도 known test account가 필요하다.
- v2 schema는 기존 v1 `general`, `general_turn`, `world_state`를 즉시 대체하지 않는다. v2 전용 `world_id`와 event/replay 테이블을 먼저 추가한다.

## 기획 원칙

1. 사용자에게는 삼모의 턴 입력 문법을 유지하되, 서버 내부는 이벤트와 명령 결과를 분리한다.
2. 자동 판단은 LLM-free, seed·score·근거를 저장해 재생 가능하게 만든다.
3. 전쟁은 수식보다 replay schema를 먼저 고정한다.
4. v1 패러티 변경과 v2 신규 규칙은 커밋·테스트·월드를 분리한다.
5. 신규 기능은 read-only → deterministic mutation → production sandbox 순서로 통과시킨다.
