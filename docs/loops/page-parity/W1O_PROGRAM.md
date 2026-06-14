# W1-O 백엔드 데이터 파이프라인 프로그램 (핸드오프 2026-06-14)

> 유저 **전체 승인**(2026-06-14). 라이브 프론트 대조 결과 = **프론트 골격 충실, 갭 본체 = 백엔드 데이터 미적재**("정보 부족=갭").
> 핵심 패턴: game-api DTO 필드가 null/blocked → 프론트 "-" or 크래시. **데몬 KV write → game-api 언블록 → 프론트 렌더**. Docker IT **1개씩**(dual-stack 금지, [[feedback_dual_stack_overload]]).

## 상태 (2026-06-14)
- ✅ 배포: main d7f2ba0e (loops 27/30/31/33/34/36/38/39/40). deployer a72e가 -b 검증 + **s1 bounce**(라이브화) 진행 중.
- ✅ 프론트 크래시 2종 graceful 가드(loop40). 데이터는 아래 파이프라인이 채움.
- 브랜치: `loop-parity-2026-06-14-c`(현재). 이전 -b는 main 머지됨.

## W1-O 루프 (각 = 데몬 write→game-api 언블록→프론트, Docker IT)
우선순위(tractable-first):
1. **nationNotice (국가방침)** — 데몬이 nation notice를 KV(nation_env/world meta)에 write → FrontInfoController.notice 언블록 → 메인/NationBasicCard 렌더. devsam PageFront.vue:30-32 `nation.notice?.msg`. 데이터(notice)는 명령으로 설정되는 기존값 → 노출만. **가장 tractable**.
2. **online tracking** — 데몬 online_nation CSV + online_user_cnt + per-nation online_generals를 world_state.config/nation_env KV write → IdentityDto onlineNations/onlineUserCnt/onlineGen 언블록(read 코드 존재) → 메인 접속국가/접속자 + 접속량정보(traffic) 페이지. presence 추적 인프라 필요(last-access 집계).
3. **nation-finance 데이터** (P0-52/53/54): income/outcome 국가-type 수입 파이프라인 game-api 조립 + policy.rate/bill/secretLimit(nation.meta 시드 P1-077) + warSettingCnt(NationEnvReadRepository 신설) + nationsList(외교관계). loop40 가드가 배선 시 dead code화.
4. **국가패널 "-" 필드**: 세율/지급률/국력/기술력/전략·외교쿨다운 + 장수패널 수비설정/실행잔여/벌점 — DTO 매핑 + 엔진 compute/KV.
5. **hall 테이블(OQ-5)** Flyway + MonthlyPipeline 시즌종료 write → 명예의전당.
6. **emperior 테이블(OQ-1)** Flyway + 통일(ConquerCity) trigger write → 왕조일람.
7. **주민 현재값** — ProcessIncome 월틱 실적용 확인(devsam 3M/4.35M vs opensamguk 4.35M/4.35M, 로컬 stale 가능).

## 기타 승인 작업
- **WS-A core2026 전수 게이트화** — 57 TS테스트 중 49커버. 미커버=integration e2e 3(Docker)·troops lifecycle·blankStart·nationMissing. 전수 통과를 동결 게이트로. (che_징병은 이미 커버=loop37 스킵)
- **WS-B docker = opensamguk-docker 매칭** — **HIGH-RISK 라이브 prod 토폴로지**. 라이브 deploy는 이미 `~/opensamguk-docker`(shared/server 분리)로 동작 중(repo docker-compose.production.yml과 divergence). 정본 문서 reconcile + deployer/socket-proxy/HTTPS. **별도 신중 세션 권장**(prod 영향). 상세=초기 docker diff(a110 에이전트 결과, 미파일).
- **일반/재야 역할 대조** — 소스기반(devsam hwe/ts ↔ web/game source) 권장(dual-stack 금지). MENU_SWEEP_CHECKLIST.md 56항목.
- **감찰부** — devsam b_inspect.php 부재 → core2026 확인 후 격리 결정.

## 일반/재야 역할 대조 결과 (acd49 소스대조, "모든 메뉴" 커버 완성)
**프론트-cheap P0(백엔드 존재):**
- **외교 승인/거부 버튼**(P0-16) — DiplomacyMessageController.accept/declineLetter 존재, FE 버튼만 누락. devsam t_diplomacy.php:153-154. **loop41 진행중**
- my-generals **벌점 컬럼** — refresh_score_total(단 general_access_log 부재면 DTO 미보유 — 확인 필요, loop31서 BLOCKED였음)
- diplomacy **permission<1 페이지 차단** 미이식(일반/재야 누구나 열람中)
**백엔드결합/BIG:**
- join **국가 선택 UI**(nationList/초대장 재활용) + 사진선택 + 유산점수(inheritPoint)
- **select-pool 전체 미이식**(j_get_select_pool/token/select_npc API 신규 필요) — BIG
- my-nation income 6종+예산(=위 W1-O #3) · troop 타도시 도시명(cityName, =cityConst 백로그) · nation_history(국가열전)
**MATCH 확인**: troop 구조, 재야 메인 게이트, diplomacy 수뇌 회수/파기/작성, my-nation 재야 exit.

## 라이브 오라클 재가동 (필요 시, 단일스택)
레시피 = [[project_devsam_docker_oracle]]. devsam :8080/sam/che + opensamguk 로컬 :3001, 4역할(general_owner 바인딩, 엔진 stop→update→restart). ⚠️동시 금지.
