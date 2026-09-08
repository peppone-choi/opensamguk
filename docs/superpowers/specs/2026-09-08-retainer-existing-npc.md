# 기존 NPC 가신 서약

Date: 2026-09-08 · Status: approved continuation (user request, coordinated design approval)

ADR-LITE-017의 EXISTING 절편을 구현한다. 신규 서약은 현재 세계 NPC를 선택하거나 같은 후보 풀에서 결정론적으로 추첨한다. 이전 RECRUITED 이름 생성 서약은 더 이상 접수하지 않으며, 저장된 RECRUITED 행의 읽기·해제·정산은 유지한다. 이 결정은 2026-09-06 수직 절편의 신규 서약 입력과 RECRUITED 생성 기대값을 대체한다.

후보는 현재 존재하는 npcState=2, userId 미소유, 자기 자신 아님, 군주 아님(officerLevel<12), 다른 가신 관계에 연결되지 않은 장수다. 국가가 주인과 같거나 재야여야 한다. 실제 장수 행의 존재가 생존 기준이며, 장수 사망은 행 삭제 경로를 쓴다. 국가·계정·관계 상태는 엔진에서 서약 처리 순간 다시 검증한다. 선택과 추첨 모두 ID 오름차순 후보 풀을 사용한다. 추첨 seed는 hiddenSeed, 세계 ID, 연월, 주인 ID, 영속 가신 ID 고수위로 구성하며 벽시계·요청 UUID·후보 삽입 순서를 쓰지 않는다. 실패는 ID를 할당하거나 자원을 차감하지 않는다.

서약 비용과 상한은 현행 규칙을 유지한다. 원래 generalId, 이름, 초상, 능력, 국가, 병력, 일반 NPC AI를 유지한다. 새 장수나 병력을 생성하지 않는다. EXISTING 관계는 generalId를 참조하고 hasOwnBugok=true, releasePolicy=MUTUAL이며 RECRUITED 월 유지비를 내지 않는다. hasOwnBugok는 독립 병력 보유 자격이며 새 부곡 행이나 병력 복사가 아니다. 역할·임무 효과는 기존 메타데이터와 부곡 훈련 효과 범위다.

주인은 해제할 수 있고 가신은 기존 충성 0 월 정산 규칙으로 떠난다. 해제는 관계와 부곡 지휘관 연결만 지우고 NPC를 보존한다. 주인 또는 연결 NPC 사망은 기존 즉시 툼스톤/FK CASCADE를 따른다. 서약 후 국가 변화에 강제 전향·강제 해제는 추가하지 않는다. 현재 일반 AI는 계속 동작하며 개인 관계와 소속 국가는 별개다. 관련 UI에 이 한계를 설명한다. 가신으로 묶인 NPC의 플레이어 빙의는 read/intake 양쪽에서 거부한다. 같은 틱 명령 순서가 경합의 승자를 정한다.

DB 부분 UNIQUE(world_id, general_id) WHERE general_id IS NOT NULL로 이중 주인을 금지한다. UI는 현재 후보 목록 선택이 기본이며 추첨 버튼을 별도 제공한다. 후보/휘하 실제 초상과 이름을 표시하고 빈 후보, 자금/상한/처리 중 상태 및 명령 결과 polling을 유지한다. 적국/소유된 장수 능력 정보를 후보로 노출하지 않는다.

검증: 자원 recorder 패치, 실제 정체성, 후보 재검증, 안정 추첨, 이중 서약, 해제/사망, 빙의 경합, 이전 행 호환, world-scoped DB unique와 flush/reload, API/화면 동작.
