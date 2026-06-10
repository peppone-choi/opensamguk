# Cross-agent critique — prod 턴동결 핫픽스 (checkStatistic aux + statistic DDL)

- 날짜: 2026-06-10
- 스코프: `hotfix/statistic-aux-serialization` (vs main)
- 구현자: Claude (Fable 5) · 크리틱: parity-reviewer 서브에이전트 (독립 PHP 오라클 검증)

## Implementer claim

prod s1/spep 양 서버가 world 181.1에서 턴 영구 동결(턴데몬 크래시루프 370회+).
원인은 **2층**:

1. `DaemonLoopConfig` CheckStatistic 훅의 `Json.encodeToString(row.aux)` —
   `aux: Map<String, Any?>` 이종 중첩 맵이라 kotlinx가 런타임
   `SerializationException("Serializer for class 'Any' is not found")` throw.
   연경계(새 달 == 1월, `MonthlyPipeline.kt` L8) tick이 영원히 실패.
   신규 월드는 첫 경계가 1월이므로 **생성 직후부터 동결**.
2. `statistic` 테이블 DDL이 마이그레이션에 전무 — 1을 고쳐도
   `relation "statistic" does not exist`로 동일 동결. (크리틱 P0 발견)

수정:
- `StatisticInsertColumns` 신설 — aux를 `MetaJson.encode`(PHP `Json::encode`
  byte-faithful: compact, UNESCAPED_UNICODE/SLASHES, insertion-order)로 인코딩.
- `V13__statistic_table.sql` — PHP `hwe/sql/schema.sql:445-461` 컬럼 순서 그대로.
  aux는 repo Postgres 관례(jsonb) 채택, decode-level 비교 계약 문서화.
- `StatisticFlushIT` — Testcontainers 실DB로 flush SQL↔DDL 정합 증명 (red→green).
- `StatisticEncodePathGuardTest` — kotlinx 경로 리버트 가드(소스 스캔).
- `StatisticInsertColumnsTest` — aux 인코딩 + 컬럼 순서 회귀 (red→green).

## Critique 결과 (verdict: fix-required → 해소 후 cleared)

| # | 심각도 | 발견 | 처리 |
|---|---|---|---|
| 1 | P0 | `statistic` DDL 부재 — 직렬화 수정만으론 SQL 크래시로 동결 지속 | ✅ V13 + StatisticFlushIT (red→green) |
| 2 | P1 | aux jsonb는 키 순서 정규화 — PHP는 TEXT utf8mb4_bin | ✅ jsonb 유지(repo 관례), decode-level 비교 계약을 V13/IT에 문서화 |
| 3 | P1 | 회귀 테스트가 크래시 지점(DaemonLoopConfig)을 안 고정 — 리버트해도 green | ✅ StatisticEncodePathGuardTest 추가 |
| 4 | P2 | 주석이 "월경계"라 함 — 실제는 연경계(newMonth==1) | ✅ 전부 연경계로 수정 |
| 5 | P2 latent | aux `nations.all`이 List — PHP는 nation id 키 dict (`convertArrayToDict`, func_gamerule.php:518-524) | 백로그 (아래) |
| 6 | P2 latent | `nations.avg` 키 순서 mintech,maxtech,avgtech,**avgpower,minpower,maxpower** — PHP SELECT 순서는 ...,minpower,maxpower,avgpower; avg값도 PHP는 pre-round 원값 | 백로그 (아래) |
| 7 | P2 latent | crewtype 히스토그램 — PHP `WHERE recent_war != NULL`은 3VL상 항상 0행(grand-truth 버그). Kotlin은 실제 매칭 | 백로그 (아래) |

latent 3건은 **이 diff가 도입한 것이 아니라 기존 CheckStatisticCalculator 포팅 갭**.
골든 없이 고치면 fabrication이므로 statistic 골든 캡처(백로그)와 함께 닫는다.

## Backlog (statistic 골든 캡처 시 함께)

- [ ] `tools/php-golden`로 checkStatistic 실 캡처 (연경계 1010 시나리오)
- [ ] aux `nations.all` → nation id 키 LinkedHashMap (power desc 삽입 순서)
- [ ] aux `nations.avg`/`generals.avg` — PHP SELECT 키 순서 + pre-round 값/타입
- [ ] crewtype 히스토그램 — PHP 3VL 버그(항상 빈 결과) 충실 재현 여부 결정
- [ ] `StatisticInsertColumnsTest` 기대 문자열을 골든 기준으로 갱신

## Clean dimensions (크리틱 확인)

- MetaJson은 PHP `Json::encode` 플래그 충실 (`src/sammo/Json.php:16`)
- 컬럼 키/순서 = PHP insert 배열(func_gamerule.php:637-650) = statisticInsertMany 파라미터
- 엔진 메인에 다른 `Json.encodeToString(Map<String,Any?>)` 지뢰 없음
- one-daemon-write rule 무영향 (순수 맵 빌드 → ChangeRecorder delta)

## Gates

- `:app:game-engine:test` 50 suites failures=0 errors=0 (rerun)
- `:infra:test` StatisticFlushIT 1/1 (Testcontainers 실DB, red→green 관찰)
- XML 검증 (exit code 비신뢰 규칙 준수)

## Verdict

Verdict: cleared — P0/P1 전부 해소, latent P2 3건은 골든 백로그로 증거와 함께 이관.
