# MILESTONES — 미래(로드맵 외) 마일스톤 원장

P0–P8 / F0–F5 / WAVE 0–9 외의, **조건 충족 시 착수**하는 미래 마일스톤. 현재 작업 아님(날조 금지 — 조건이 실제로 충족됐는지 먼저 검증).

---

## M-config — 안정화 후 게임/서버 상수 외부화 (JSON/DB)

**Status:** 미래 마일스톤. NOT started. **선행조건 = 동결 회귀 green + 운영 안정.**

> **전제 변경 (2026-08-20, ADR-LITE-042).** 현재 제품 정본은 승인 ADR/spec·구현이다. v1 기존 테스트와 골든은 지우지 않는 동결 회귀 기준선이고 PHP는 opt-in 역사 비교 자료다. 상수 외부화는 PHP 패러티 close를 선행 조건으로 삼지 않는다.

### 무엇

패러티-load-bearing 상수(`common/constants/GameConst` 등 — RNG/공식/로그-임계에 영향하는 값)를 **컴파일 상수에서 JSON(또는 DB) config로 외부화**.

### 언제 (트리거 조건 — 둘 다 충족해야)

1. **현재 테스트 disposition 완료** — 기존 GoldenTest / ReplayGateTest / GateTest를 포함한 현재 승인 테스트가 green이고, 남은 제품 갭이 승인 spec·proof·백로그로 disposition됨.
2. **운영 안정** — 라이브 prod가 무크래시로 턴 진행 + 핵심 ops(시드/배포/복구) 검증 완료.

이 두 조건은 제품 안정화 시점을 뜻한다. PHP 오라클 완료 여부와 무관하게 오픈삼국의 승인 ADR/spec·현재 구현이 제품 정본이며, DB/JSON 가변화는 런타임 오타가 동결 회귀를 **조용히** 깨지 않도록 보호해야 한다.

### 어떻게 (load-bearing 규칙 — "패러티 게이트 → frozen-baseline 게이트" 인수인계)

패러티 테스트는 **은퇴하되 사라지지 않는다.** JSON 오타가 라이브 밸런스를 조용히 깨는 걸 막는 net으로 역할 전환:

1. **스냅샷.** 외부화 시점의 골든-closed 상수 값을 JSON 커밋 baseline으로 동결.
2. **게이트 교체.** 패러티 골든(=PHP와 byte-일치)을 **"config-load가 frozen baseline을 재현"** 회귀 테스트로 교체. 골든의 역할이 *"PHP와 일치" → "런칭 baseline과 일치"*로 전환.
3. **변경 게이트.** JSON/DB-load config 변경은 리뷰 + 스냅샷 테스트 뒤에만 → admin/운영이 잘못된 값을 라이브에 푸시 불가.

### 경계 (Non-goals — 이미 올바르게 외부화됨, 재작업 금지)

- **per-game/시나리오 데이터**(도시·장수·시작연도·turnterm·국가수) — 이미 `scenario/scenario_*.json` + `scenario/cities_*.json`. 신규 시나리오는 이 패턴 확장(M-config 대상 아님).
- **서버/ops 튜너블**(tickSeconds·commandBlockMs·`SCENARIO_SEED_ENABLED`·`ADMIN_*` 등) — 이미 env + `world_state` meta + `game_kv` KV. 비-패러티 값만.

### 경계 규칙 (요약)

> "골든 draw/log/공식에 영향? → (지금은) 코드, (parity-closed 후) M-config로 JSON.
>  per-game 시드 or ops 튜너블? → 이미 JSON/KV."

### DB vs JSON

메모리-centric 데몬은 상수를 부팅 1회 로드 → DB 왕복은 지연+실패모드만 추가. 기본 **JSON**(버전관리 + 스냅샷-게이트 친화). admin 실시간 밸런스 편집이 진짜 필요할 때만 DB/KV(단 §어떻게의 변경 게이트 필수).
