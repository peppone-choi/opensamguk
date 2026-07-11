# 5스탯(정치·매력) RTK14 divergence 스펙 — 2026-06-13

**상태: 초안 — Track B는 승인 대기.** 루프: `docs/loops/5stat-divergence-2026-06-13/`.

## 결정 (유저, 2026-06-13)

1. **정치(politics)·매력(charm) 추가** → 오픈삼국 5스탯. 레거시 devsam/core(3스탯)에서 의도적 divergence. 1.0.0+ 독자기능.
2. **값 소스 = 삼국지14 무장정보.xlsx** (현재 입력 1000행, 통무지정매 완비). **코에이 IP** → 원본·source JSON·생성 시나리오 절대 커밋 금지. `tools/rtk14/build_rtk14_stats.py` 알고리즘만 커밋한다.
3. **스탯값 범위 = devsam 통무지 유지 + 정치·매력만 RTK14에서 추가.** 통/무/지는 scenario 그대로(패러티 보존).
4. **이름 keying** — 모든 시나리오의 devsam 장수명 ↔ RTK14 무장명. 끝자리 숫자, 자가 붙은 이름, 명시 별칭을 정규화한 뒤 통무지·생몰년 거리로 동명이인을 1:1 배정한다. 원본에 없는 인물만 정치/매력 50/50 fallback을 사용한다.
5. **Track B(로직 대체) 영역:** 내정(개발 농/상/기)→정치, 등용/임관→매력, 민심/인구→매력, 외교→정치/매력.
6. **시퀀스:** 둘 같이(Track A 실행 + Track B 스펙 후 승인 게이트).

## Track A — 패러티 무손상 (즉시 실행 가능)

기존 골든 green 유지가 게이트. 순수 추가.

- **W1 ✅** `General`(logic `LogicEntities.kt`)에 `politics:Int=0`/`charm:Int=0` inert 필드(끝 append). 커널 2315 green 무회귀.
- **W2 ✅** all-scenario 빌더: 저장소의 30개 `scenario_*.json`을 읽어 장수 10,176개 tuple의 인덱스 14/15에 정치·매력 원수치를 직접 주입. `SCENARIO_DIR` 외부 파일 우선, classpath 폴백.
- **W3** 영속화: Flyway `V*__add_politics_charm.sql`(general.politics/charm int default 0) + JdbcFlushExecutor 매퍼 + JPA read 엔티티 + ChangeRecorder. 게이트: infra flush IT green(Docker).
- **W4 ✅** UI/API: 장수 정보·랭킹·카드·NPC 정책·장수 등록에 정치/매력을 노출한다. 유저 생성은 5개 슬라이더와 총합 275 상한을 사용하며 API·wire·engine·flush가 동일 값을 보존한다.

## Track B — 플래그 게이트 divergence (✅승인됨 + 강화된 격리)

**핵심 아키텍처(fresh 리뷰 a8111801 반영): divergence 플래그.** 정치·매력 주입은 전부 플래그 뒤.
- 플래그 **off** = devsam 통무지 = 0.9.0 패러티 → **devsam-baseline 골든 계속 green**(RED 아님, archive 아님).
- 플래그 **on** = 정치/매력 동작 → **신규 divergence 골든** 별도 신설.
- 주입 설계는 off 경로가 baseline과 **draw-for-draw 동일**하도록(예: `if (fiveStat) use politics else getStatValue("intel")`).
- **비-RNG 산정식에만**(내정·등용·외교). **전투·AI선택·RNG draw 절대 금지**(rule 1 불가침).

각 영역 = 별도 바퀴: 플래그 분기 추가 + 신규 divergence 골든 + fresh 재채점. baseline 골든 무수정·무완화.
플래그 위치: `GameConst`(또는 per-server config) — 0.9.0=off, 1.0.0=on. 리서치 워크플로 wf_b01fab06이 주입점/가드골든 매핑 중.

**리서치 확정(wf_b01fab06, 4 Explore agents):**

| 바퀴 | 영역 | 상태 | call site(flag-gated swap) | 가드 골든(flag-off green 유지) |
|---|---|---|---|---|
| **B1** | 내정 개발(농/상/기) | ✅ **swap가능** | `CommerceInvestment.kt:68`(intel→politics) + `develop/CheGisulYeongu.kt:70` | DevelopGoldenTest, CommerceActionLogGoldenTest, che-action-fixtures.json |
| **B3** | 민심/인구 | ✅ **swap가능** | `develop/CheJuminSeonjeong.kt:66` + `develop/CheJeongchakJangnyeo.kt:81`(leadership→charm) | 이미 quarantine(DEVELOP_CAPTURE_DEFECT) + 유닛 테스트 |
| **B2** | 등용(매력) | ✅ **완료**(커밋 4f04761) | CheDeungyongSurak: 모집자 매력이 배신 평판패널티 완화 `factor=1-0.1·betray·(1-charm/200)` | CheDeungyongSurakGoldenTest 20 (flag-off green) |
| **B4** | 외교(정치) | ✅ **완료**(커밋 4f04761) | 종전/불가침/불가침파기 수락: 정치<30→실패+거절로그+효과 skip(DiplomacyDivergence) | CheJongjeonSuak/CheBulgachimSuakGoldenTest (flag-off green) |

**B2/B4 설계(유저 결정 2026-06-13):** 두 명령군은 수동 제안+수락이라 확률 슬롯이 없어 **결정적** 공식으로 구현(RNG 없음 → carve-out "비-RNG" 유지, 규칙변경 불요). 등용=매력 평판완화(기존 betray 수량 부착), 외교=정치 수락 성공게이트(BAR 30). 둘 다 flag-off=baseline byte-동일. 신규 divergence 행동테스트(오라클 없음).

**B0 (foundation, B1·B3 선결):**
- `GetStatValue.raw()`에 `"politics"`/`"charm"` 케이스 **additive** 추가(기존 leadership/strength/intel when-branch 불변 — 전투/RNG 공유함수라 내부 동작 무변경, 신규 이름만 인식).
- divergence **플래그** `GameConst.FIVE_STAT_DOMESTIC`(off 기본=패러티). swap call site: `if (FIVE_STAT_DOMESTIC) "politics" else statName`.
- ⚠️ 공유헬퍼 `DomesticHelpers.criticalRatioDomestic` **불변**(전 내정명령 공유) — score read call site만 교체.

**B1·B3 divergence 테스트(오라클 없음 → 행동 테스트):** flag-on → score가 politics/charm로 구동됨(정치≠지력인 장수가 flag-off 대비 다른 score), flag-off → baseline과 byte-동일. 가짜 골든 날조 금지.

**Track B 승인 항목:**
- 영향 골든을 어떻게? (a) devsam-baseline로 quarantine 보존 + 신규 divergence 골든 신설, (b) 기존 골든 재생성. → 유저 결정.
- 각 영역 신규 공식의 구체 계수(정치/매력을 지력/통솔 자리에 1:1 치환? 가중 혼합?) → 영역별 스펙 필요.

## 프론트 secondary 처리 (2026-06-13)

- ✅ **generals(전체 장수)**: `/api/generals`→PublicGeneral 소비(positional general-list 아님 — W4 understand 오라벨 정정). PublicGeneral에 정치/매력 이미 있어 COLUMNS+SortKey+셀 추가로 close. tsc green.
- ⏭️ **admin5(일제 정보)**: grand-truth `_admin5.php` **28열 verbatim 패러티 테이블**. 정치/매력 컬럼 추가 = 패러티 깨짐 → **스킵**. 1.0.0에서 신규 divergence stat-type로 추가 가능.
- ✅ **join(장수 등록)**: 통솔·무력·지력·정치·매력 5개 할당 폼과 총합 275 상한. create-path intake와 JWT owner 경계, DB flush까지 연결.
- ✅ **inherit(유산)**: 통무지 리셋은 기존 패러티 총합을 유지하고 현재 정치·매력은 그대로 보존해 유실하지 않는다.

결론: 표시뿐 아니라 NPC 시드, 유저 생성, 유산 리셋 보존, API/wire/flush 라운드트립까지 5능력치 경계를 닫는다. admin5의 레거시 28열 표는 별도 divergence 화면 없이 유지한다.

## 운영 backlog

- ✅ **prod 사이드로드:** 별도 stats lookup을 제거하고 완성된 `scenario_*.json` 자체를 덮어쓴다. workflow가 `RTK14_STATS_JSON_B64` source secret과 checkout 시나리오를 결합해 gitignored 외부 디렉터리를 생성하고, 엔진은 `SCENARIO_DIR=/data/scenarios`를 classpath보다 우선한다.
- `AvailableCommandsControllerTest` 단위 실패 1건(베이스라인 backend) — 별도 조사(W5).

## W6 — CLAUDE.md divergence carve-out 삽입안 (⚠️ 승인 대기 = 규칙 변경)

아래는 CLAUDE.md "Parity discipline (NON-NEGOTIABLE)" 5번 뒤에 추가할 **제안 문구**. 패러티 규율을 약화하지
않도록 정치·매력에만 한정·격리한다. **유저 승인 전 CLAUDE.md 미수정.**

> **Sanctioned divergence (1.0.0+, narrowly scoped).** `politics`(정치)/`charm`(매력)은 레거시 devsam/core에
> 없는 오픈삼국 독자 스탯이다. 이 둘은 PHP 골든 오라클이 없으므로 규율 5번의 "faithful capture" 대상이
> **아니다** — 출처는 RTK14(삼국지14, 코에이 IP → 원본/source JSON/생성 시나리오 git-ignore). 그러나 격리 규칙은
> 엄격하다: (a) 통/무/지(leadership/strength/intel)의 `getStatValue`·RNG draw·로그·골든은 **불변**(rule 1–6
> 그대로). (b) 정치·매력을 기존 패러티 공식에 주입하는 변경(Track B)은 영향 골든을 **재생성하지 않고**
> devsam-baseline로 quarantine 보존 + 신규 divergence 골든을 별도 신설한다. (c) 정치·매력 값은 fabricate가
> 아니라 RTK14 캡처 + 동명이인 지문배정으로만 채운다(미매칭은 기본 50). 스펙: `docs/superpowers/specs/2026-06-13-five-stat-rtk14-divergence.md`.

추가로 W6에서 적용할 **서술형(규칙 아님 → 승인 후 즉시 적용 가능)** doc 갱신:
- 로드맵 status에 5스탯 divergence 항목 1줄.
- README/AGENTS 스탯 설명에 정치·매력 추가.
- 메모리 `project_versioning_0_9_parity`에 5스탯 divergence 사례 링크.

## 불변 (frozen — 변경 시 유저 승인)

- `docs/loops/5stat-divergence-2026-06-13/GOLDENSET.md`
- devsam 통무지 패러티 골든(Track B 영향 영역 제외)
- 코에이 IP 미커밋 규칙
