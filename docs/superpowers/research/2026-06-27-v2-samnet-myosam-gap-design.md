# opensamguk v2 조사 및 설계 메모

Date: 2026-06-27
Status: draft

## 입력 소스

- `https://www.samnet.kr/`: 현재 공개 화면 관측. 첫 화면에서 `218년 11월 하순`, 2D 지도 토글, 황건적 토벌, 최근 정세 로그가 노출된다.
- `http://www.myosam.com/dokuwiki/doku.php?id=help:start`: help namespace 전체 37페이지 수집 완료.
  - Raw: `docs/wiki/raw/myosam-help/MANIFEST.md`
  - 수집 스크립트: `tools/wiki/scrape_myosam_help.py`
- `/Users/apple/Downloads/files/`: v2 PRD/ROADMAP/CLAUDE/KICKOFF 4개 문서 확인 및 raw 복사.
  - Raw copy: `docs/wiki/raw/opensamguk-v2-downloads/`

## 현재 v1과 외부 표면의 주요 차이

### 시간과 표기

현재 브랜치에서 `current_phase`를 도입해 `상순/중순/하순`을 world state, read API, reserved command, realtime event, flush path에 관통시키는 중이다. samnet 공개 화면도 `218년 11월 하순`을 직접 노출하므로 v2뿐 아니라 v1 하네스 갭으로도 닫아야 한다.

v2 PRD는 더 급진적이다. 200ms 서버 tick, 개인별 장수 턴 주기, `ticks_per_game_sun` 기반 1순 진행을 제안한다. v1의 `turnterm` 기반 월/순 계산과 충돌하지 않게 하려면 “표현용 game date”와 “명령 실행 cadence”를 분리해야 한다.

### 전쟁

myosam help는 전쟁을 단일 수치 충돌로 설명하지 않는다.

- 전장 위상: 야전/요격전, 공성전/농성전, 시가전.
- 수비 시스템: 요격 장수가 없으면 공성/농성으로 넘어가고, 손실 병사 일부가 도시 병사로 복귀한다.
- 건설물/장애물: 요새/성채, 야전병원, 진, 궁노/연노, 북악대/군악대, 투석대, 목책/철책이 전투 전후 또는 0페이즈에 발동한다.
- 부대: 부대장은 집합으로 장수를 모으고, 부대 전술은 통상/야전/공성/농성으로 전쟁 흐름을 바꾼다.

따라서 v2 전쟁은 “깡대깡 전투력 비교”가 아니라 전장 상태 머신이어야 한다. 최소 구조:

1. 접근/기동: 출병 경로, 속도, 지형, 차단, 정찰 정보를 계산한다.
2. 조우: 수비 방침과 요격 가능 병력이 야전/요격 여부를 결정한다.
3. 공성/농성: 요격 실패 또는 부재 시 성벽, 요새, 공성병기, 도시 병사가 관여한다.
4. 시가전: 성벽 돌파 후 도시 내부 피해, 약탈/탈취, 민심/치안 손실을 처리한다.
5. 전후 처리: 병력 복귀, 포로/부상, 창고 탈취, 점령/분쟁 갱신, 로그/리플레이를 남긴다.

핵심 구현 원칙은 deterministic replay다. v1 battle parity처럼 RNG draw stream과 phase log를 고정하되, v2는 추가로 `BattleReplay` JSON에 기동, 지형, 시설 발동, 명령 편향, 보급 상태를 남겨 UI가 전투를 재생할 수 있게 해야 한다.

### NPC, 추종, 가신

myosam NPC는 일반 유저 턴을 수행하지만 장수 임명, 부대 창설, 지휘부 턴 입력 같은 고급 플레이는 수행하지 않는다고 설명한다. v2 PRD는 여기서 두 트랙을 제안한다.

- 추종: 기존 장수(NPC/재야/유저)가 주장에게 서약한다. 독립 병력/창고/턴을 유지하며 동시침공, 집결명령, 광역이동 대상이 된다.
- 가신: 신규 NPC 지원자다. 독립 병력/턴 없이 주장 전용 버프, 대행, 조언, 탐색, 외교 보조를 제공하고 월별 유지비를 먹는다.

사용자 추가 요구인 “주인공 캐릭터 아래의 NPC 캐릭터와의 상호작용 및 명령과 제안”은 가신을 단순 buff row가 아니라 관계형 agent로 만들어야 한다.

권장 모델:

- `general_retainers`: PRD의 5유형(참모/호위/군수관/정찰/사신)을 유지하되, `loyalty`, `trust`, `ambition`, `temperament`, `bias_json`, `proposal_cooldown_until`, `assignment`을 추가한다.
- `retainer_interactions`: 칭찬, 질책, 포상, 상담, 임무부여, 해임, 비밀지시 같은 상호작용 로그.
- `retainer_proposals`: 가신이 생성한 제안. 예: “서쪽 전선 병량 부족”, “A 장수는 공성보다 야전에 적합”, “B 세력과 휴전 제안”.
- `retainer_assignments`: 참모=전략 제안, 호위=부상/암살 위험 완화, 군수관=보급/창고 효율, 정찰=전장 정보/매복 탐지, 사신=외교 제안/교섭 보정.

제안은 LLM-free여야 한다. 런타임 문장은 템플릿으로 만들고, 근거는 world state query + rule score로 계산한다.

### 편견과 어전회의

“편견(어전회의 등)”은 정치 AI의 핵심으로 분리한다. 제안 자체보다 더 중요한 것은 누가 어떤 이유로 제안을 믿거나 거부하는지다.

권장 모델:

- `court_councils`: 군주/부세력/주장 단위 회의 인스턴스. 의제, 참석자, 결론, 시행 명령을 가진다.
- `council_opinions`: 각 참석자 의견. `stance`, `confidence`, `bias_factors`, `relationship_modifiers`.
- `bias_profile`: 성격, 출신, 관계, 이해관계, 전공/문관 성향, 최근 피해 경험, 충성/야망에 따른 가중치.
- `proposal_resolution`: 군주가 수동 선택하거나, NPC 군주라면 점수 기반으로 채택한다.

예시 편견:

- 사신 가신은 외교 해법을 과대평가한다.
- 호위 가신은 군주 신변 위험을 크게 본다.
- 군수관은 보급 부족 시 공격 제안을 강하게 반대한다.
- 전공 성향 장수는 소모전/강습을 선호한다.
- 피해를 본 도시 출신 장수는 보복 전쟁을 지지한다.

어전회의는 UI에서도 “정답 추천”이 아니라 의견 충돌을 보여줘야 한다. 플레이어가 군주라면 최종 결정을 내리고, NPC 세력이라면 ruler AI가 편견 합산으로 결정한다.

### 부세력과 봉건제

기존 위키에는 `봉국 할당`과 9단계 관직/황제 확장 지식이 있고, 다운로드 PRD에는 `imperial_status`, `emperor_type`, `suzerain_id`가 있다. 사용자의 “각 세력은 부세력을 가지고 있고, 봉건제의 개념” 요구는 이 둘을 합쳐야 한다.

권장 모델:

- `nations`: 주권 세력. `imperial_status`, `suzerain_id`, `legitimacy`, `court_rank`.
- `subfactions`: 한 nation 내부의 봉토/군단/문벌/속령. 자체 leader, capital city, resource share, levy quota, autonomy를 가진다.
- `fiefs`: 도시 또는 도시 묶음을 특정 장수/부세력에 봉한다. 세금/병력/자원 분배율을 가진다.
- `feudal_contracts`: 주군-봉신 관계. 조공, 원군 의무, 독립권, 외교권, 배반 조건.
- `subfaction_councils`: 부세력 내부 의사결정. 중앙 명령을 받아들이거나 지연/왜곡할 수 있다.

게임 효과:

- 군주는 모든 도시를 직접 조작하지 않고 봉토/태수/부세력에 권한을 위임한다.
- 부세력은 보급, 징병, 수비, 건설 우선순위를 자체적으로 가진다.
- 봉신의 충성/자율성이 낮으면 명령 지연, 소극적 원군, 독자 외교, 반란이 가능하다.
- 황제/왕/공 레벨은 단순 칭호가 아니라 봉건 계약 슬롯, 봉신 수, 조공/징병 권한에 영향을 준다.

## v2 구현 순서 제안

1. **Evidence layer**: myosam raw 37페이지를 위키 페이지로 컴파일하고, samnet 공개 화면의 현재 기능 차이를 스냅샷한다.
2. **v1 clock close**: `current_phase`를 통과시키고 backend gate를 닫는다.
3. **v2 domain schema spike**: `retainers`, `followers`, `subfactions`, `fiefs`, `court_councils`, `battle_replays`를 ERD로 확정한다.
4. **dynamic war vertical slice**: 하나의 출병이 접근 → 조우 → 공성/시가전 → 전후처리 replay를 생성하게 만든다. 처음에는 수치 단순화 가능하지만 phase log와 replay schema는 고정한다.
5. **retainer proposal slice**: 참모 가신 1명이 전쟁/외교/내정 제안 1개를 생성하고 어전회의에서 의견 충돌로 표시한다.
6. **feudal slice**: 한 도시를 봉토로 부여하고, 봉신이 세금/징병 일부를 중앙에 납부하며 명령을 지연/수락하는 루프를 만든다.

## 고정할 비목표

- 런타임 LLM 호출 금지. 가신 제안과 회의 발언은 룰 점수 + 템플릿으로 생성한다.
- 골든/게이트 약화 금지. v1 패러티 작업과 v2 확장 작업은 브랜치/문서/테스트 목적을 분리한다.
- samnet/myosam을 그대로 복제하지 않는다. 시스템 차이는 참고하되, IP/자산은 직접 사용하지 않는다.

## 바로 이어서 할 작업

- `docs/wiki/raw/myosam-help`의 37페이지를 `docs/wiki/pages/refs/myosam-help-corpus.md`와 관련 game/data 페이지로 컴파일.
- v2 ERD 초안을 `docs/superpowers/specs/`에 작성.
- `current_phase` 브랜치의 `:app:game-api:test`, `:app:game-engine:test`, `:infra:test` 통과 후 commit/merge/push.
