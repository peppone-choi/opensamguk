# W3-2 — log_entry enum `::text` 캐스트 제거 + V29 연월 인덱스 (OPENSAM-14)

- 날짜: 2026-07-17 (KST)
- 범위: `app/game-api` read 리포지토리 4개 (`web/` 변경 없음) + `infra` Flyway V29 + IT
- Jira: OPENSAM-14 · Agent OS 활성화 계획 W3-2 시연 (EXPLAIN ANALYZE = 1차 증거)

## 문제

`log_entry.scope`/`category`는 postgres ENUM인데, read 리포지토리 4개(WorldLog·NationLog·
AdminGeneralLog·LogFeed)의 네이티브 쿼리가 **컬럼을 `::text`로 캐스트**해 비교했다. enum→text
캐스트는 binary-coercible이 아니어서 기존 인덱스 3종 `(scope, category, id)` /
`(general_id, category, id)` / `(nation_id, category, id)`이 전부 무력화 — 매 턴 성장하는 게임
최대 테이블이 조회마다 스캔됐다(메인 피드 폴링·월드 로그·국가 실록·어드민 장수 로그 전부).

## 측정 (1차 증거 — DB 레벨)

환경: 로컬 docker `postgres:16-alpine`(EC2 정지로 prod 대체 — 계획 합의), V1–V28 전체 적용,
log_entry **2,000,000행** 합성 시드(GENERAL/ACTION 70% · GENERAL/HISTORY 13% ·
GENERAL/BATTLE_BRIEF 7% · NATION/HISTORY 5% · SYSTEM/SUMMARY 3% · SYSTEM/HISTORY 2%),
시드 후 ANALYZE. 수치는 EXPLAIN (ANALYZE, BUFFERS) Execution Time.

| # | 쿼리 (라이브 경로) | BEFORE (`::text`) | AFTER (캐스트 제거) | 배율 |
|---|---|---|---|---|
| 1 | WorldLog: SYSTEM+IN(HISTORY,SUMMARY) LIMIT 50 | 26.9 ms (pkey 역스캔+필터) | **0.67 ms** (인덱스 역스캔) | 40× |
| 2 | LogFeed 메인: SYSTEM+HISTORY LIMIT 30 | 31.7 ms | **0.22 ms** (scope_idx) | 142× |
| 3 | NationLog: NATION+nation_id+HISTORY | 40.8 ms | **8.6 ms** | 4.7× |
| 4 | LogFeed 개인 폴링: GENERAL+general_id+ACTION+id≥ | 4.6 ms | **0.63 ms** | 7× |
| 5 | 연월 조회(HISTORY, LIMIT 없음) | 315.7 ms (병렬 seq) | 531 ms (**회귀** — scope_idx 4만행 비트맵) | 0.6× |
| 6 | 연월 조회 + V29 `(year, month, id)` | 315.7 ms | **13.9 ms** (BitmapAnd) | 23× |
| 7 | 연월 조회(SUMMARY/ACTION) + V29 | ~315 ms | **20.6 ms** | 15× |

부수 관측: BEFORE는 플래너 추정도 왜곡한다(#1에서 실제 10만 행 매치를 rows=42로 추정 —
캐스트 표현식엔 통계가 없음).

## 판단

1. **캐스트 제거**: #1–#4 핫패스 압승. 단 #5(연월 조회 2개)는 캐스트 제거만으로는 **회귀**
   (seq 315ms → 비트맵 531ms, 콜드 힙 랜덤 페치) — 알려진 회귀는 출하 불가.
2. **V29 `log_entry_year_month_idx (year, month, id)`**: #5 회귀를 13.9/20.6ms로 해소.
   `CREATE INDEX CONCURRENTLY` + `.conf`(executeInTransaction=false) — 라이브 테이블 배타 락이
   turn daemon flush를 얼리는 것 방지(frozen-turn-daemon 증상). 드롭-후-생성 관용구로 재시도 안전.
3. **Flyway 데드락 발견·수정 (마이그레이션 IT의 성과)**: V29 최초 실행이 IT에서 무한 행 —
   Flyway 10 기본 트랜잭션형 advisory lock이 잠금 커넥션을 idle-in-transaction으로 유지하고,
   `CREATE INDEX CONCURRENTLY`는 그 열린 트랜잭션 종료를 영원히 대기(pg_stat_activity로 실증:
   `wait_event=virtualxid` + 13분 idle-in-transaction 잠금 세션). **이대로 배포했다면 프로덕션
   마이그레이션이 그대로 행**. 공식 문서 해법 `flyway.postgresql.transactional.lock=false`(세션
   락 전환)를 앱 yml 3종(`spring.flyway.postgresql.transactional-lock`) + 마이그레이션 IT에 적용.
4. **의도적 예외**: `LogFeedReadRepository.findRecentByScopeAndCategory`는 `::text` 유지 —
   라이브 호출부(AuctionController)가 enum에 없는 P6 버그 리터럴("action"/"auction")을 넘기며,
   enum CAST 시 '없는 값 → 0행' 계약이 SQL 에러로 바뀌어 경매 페이지가 500이 된다.
   P6 리터럴 정정 시 함께 전환(KDoc 명시).

## 기각된 후보 (증거 기록 — 무근거 인덱스 방지)

- `general/city (nation_id)`: PHP 원본(hwe schema.sql)도 PK 외 인덱스 없음(의도적 설계),
  테이블 유계(수백~수천 행). 실측 Tier A(1,000장수/94도시): 단건 0.07–0.47ms, `city`는 인덱스
  생성 후에도 플래너가 seq scan 유지(94행) — 인덱스 사용 자체가 없음. 기각.
- `ng_betting`: V7이 PHP UNIQUE 인덱스 3종(by_general/by_bet/by_user)을 전부 이식 완료 —
  `findByBettingId`/`findByGeneralId` 모두 커버. 기각.

## 검증 (증거)

- `V29LogEntryYearMonthIndexMigrationTest` (Testcontainers): 전체 마이그레이트 후 인덱스
  존재+`indisvalid`+컬럼 구성 검증, INVALID 잔흔 대체(재시도 안전) 검증. — 결과는 PR에 기록.
- 기존 `LogEntryReadRepositoryIT` 등 game-api IT(Testcontainers 실 postgres)가 수정된 네이티브
  쿼리(enum-네이티브 리터럴 + `CAST(:category AS log_category)`)를 실 enum 대상으로 검증.
- 게이트: `tools/parity/gate.sh backend` XML 판정. — 결과는 PR에 기록.
- Playwright 응답시간 보조 지표: 로컬 풀스택 미기동 — **채점대기** (계획상 보조 지표,
  Architect F5 '노이즈 많은 프록시'; DB 레벨이 1차 증거).

## 크로스-에이전트 비평

- 독립 리뷰: PR 리뷰봇 2종(CodeRabbit + Claude GHA 파리티 프롬프트)이 PR에서 수행 — 이 PR이
  수정된 `claude_review.yml`(게시 도구 추가)의 최종 스모크 판정 대상.
- 본 문서 자체가 측정-주도 재조준(후보 2건 기각 근거 포함)의 감사 추적이다.

Scope: app/game-api read 리포지토리 4종 · infra/ Flyway V29+마이그레이션 IT · app/game-engine, app/gateway-api application.yml (Flyway 세션 락 설정만 — 판단 3 참조, 로직 변경 없음; web 변경 없음)
Verdict: cleared
