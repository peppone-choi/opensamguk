# 5스탯(정치·매력) RTK14 divergence 스펙 — 2026-06-13

**상태: 초안 — Track B는 승인 대기.** 루프: `docs/loops/5stat-divergence-2026-06-13/`.

## 결정 (유저, 2026-06-13)

1. **정치(politics)·매력(charm) 추가** → 오픈삼국 5스탯. 레거시 devsam/core(3스탯)에서 의도적 divergence. 1.0.0+ 독자기능.
2. **값 소스 = 삼국지14 무장정보.xlsx** (RTK14, 955 무장, 통무지정매 완비). **코에이 IP** → 절대 커밋 금지. git-ignored 로컬 리소스 `infra/src/main/resources/scenario/rtk14_stats.local.json`(955건, 생성됨)로만 로드.
3. **스탯값 범위 = devsam 통무지 유지 + 정치·매력만 RTK14에서 추가.** 통/무/지는 scenario 그대로(패러티 보존).
4. **이름 keying** — devsam 장수명 ↔ RTK14 무장명. 직매칭 439/491(89%). 끝자리 숫자 정규화(`장소1→장소`)로 ~95%+. 잔여 미매칭 → fallback(파생 또는 0).
5. **Track B(로직 대체) 영역:** 내정(개발 농/상/기)→정치, 등용/임관→매력, 민심/인구→매력, 외교→정치/매력.
6. **시퀀스:** 둘 같이(Track A 실행 + Track B 스펙 후 승인 게이트).

## Track A — 패러티 무손상 (즉시 실행 가능)

기존 골든 green 유지가 게이트. 순수 추가.

- **W1 ✅** `General`(logic `LogicEntities.kt`)에 `politics:Int=0`/`charm:Int=0` inert 필드(끝 append). 커널 2315 green 무회귀.
- **W2** RTK14 로더: `rtk14_stats.local.json`(없으면 graceful skip) → 이름정규화 매칭 → 정치/매력 lookup. ScenarioImporter가 seed 시 주입.
- **W3** 영속화: Flyway `V*__add_politics_charm.sql`(general.politics/charm int default 0) + JdbcFlushExecutor 매퍼 + JPA read 엔티티 + ChangeRecorder. 게이트: infra flush IT green(Docker).
- **W4** UI: web/game(b_myGenInfo·랭킹·장수카드) + web/gateway 정치/매력 표시. 두 맵뷰어 불변식 유지. 게이트: tsc + 비주얼.

## Track B — 패러티 일부 포기 (⚠️ 승인 대기 — 골든 재기준선 = 규칙 변경)

정치·매력이 기존 공식 대체 → 해당 골든 RED. **이건 패러티 규율(CLAUDE.md) 변경 = 유저 승인 필수.**
승인 전 blind 착수 금지(오라클 없는 공식 = fabricate 위반). 각 영역 = 별도 바퀴, 영향 골든만 명시적 재기준선 + LEDGER 기록.

| 바퀴 | 영역 | 현재 공식(PHP/Kotlin) | 신규(divergence) | 영향 골든 |
|---|---|---|---|---|
| B1 | 내정 개발 | che_농업/상업/기술 = 지력 기반 (`ActionSpecialDomestic`) | 정치 기반 | che_농업 등 *GoldenTest |
| B2 | 등용/임관 | 매력 무관 현행 | 매력 가중 | 등용 골든 |
| B3 | 민심/인구 | 통솔(인덕) 기반 | 매력 기반 | 민심/인구 골든 |
| B4 | 외교 | 현행 | 정치/매력 가중 | 외교 골든(RNG 적음) |

**Track B 승인 항목:**
- 영향 골든을 어떻게? (a) devsam-baseline로 quarantine 보존 + 신규 divergence 골든 신설, (b) 기존 골든 재생성. → 유저 결정.
- 각 영역 신규 공식의 구체 계수(정치/매력을 지력/통솔 자리에 1:1 치환? 가중 혼합?) → 영역별 스펙 필요.

## 운영 backlog

- **prod 사이드로드:** RTK14 JSON 미커밋 → prod 배포 시 별도 경로로 서버에 전달 필요(시드 전). 미해결.
- `AvailableCommandsControllerTest` 단위 실패 1건(베이스라인 backend) — 별도 조사(W5).

## 불변 (frozen — 변경 시 유저 승인)

- `docs/loops/5stat-divergence-2026-06-13/GOLDENSET.md`
- devsam 통무지 패러티 골든(Track B 영향 영역 제외)
- 코에이 IP 미커밋 규칙
