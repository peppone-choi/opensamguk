# OPENSAM-108 국내 삼모 계열 시스템 차분 조사

- 조사일: 2026-08-13 (Asia/Seoul)
- 대상: 묘삼(묘섭), `samnet.kr`(칠랑섭), 현재 OpenSamguk
- 기준선: PHP `devsam/core` 패러티를 보유한 OpenSamguk v1
- 상태: `INCOMPLETE_BLOCKED` — 공개 표면 조사는 정리했지만 묘삼·samnet 항목의 PHP/Vue devsam 차등은 `DIFFERENTIAL-UNKNOWN`이다. 해당 source path:line 대조 전에는 OPENSAM-108을 완료 처리하거나 이 조사만으로 후속 release 결정을 내리지 않는다.
- 추적: [GitHub #251 / OPENSAM-108](https://github.com/peppone-choi/opensamguk/issues/251), 상위 [GitHub #250 / OPENSAM-107](https://github.com/peppone-choi/opensamguk/issues/250)

## 1. 조사 계약과 증거 등급

이번 조사는 공개 페이지와 저장소의 현재 추적 파일만 읽었다. 계정 생성·로그인·폼 제출·대량 수집·사이트 변경은 하지 않았다. 화면 자산과 원문을 복제하지 않고 규칙을 요약했다.

| 등급 | 의미 |
|---|---|
| `CURRENT-PUBLIC` | 2026-08-13에 로그인 없이 공개 URL과 본문을 직접 관찰. 콘텐츠의 최신성은 별도 기록 |
| `HISTORICAL-PUBLIC` | 현재 접근 가능하더라도 페이지 자체의 작성·수정 시점이 오래된 공개 도움말 |
| `REPO-OBSERVED` | 현재 `origin/main` 코드·문서·열린 이슈에서 확인 |
| `UNKNOWN` | 인증, 사라진 페이지, 불충분한 공개 설명 때문에 판정 불가 |

증거가 보여주는 범위를 넘어서지 않는다. 예를 들어 공개 전쟁 목록의 `공성` 라벨은 공성 결과가 있다는 증거지만, 피해식·RNG·부대 AI의 증거는 아니다.

## 2. 소스 원장

| 관찰일 | 소스 | URL 또는 저장소 경로 | 상태와 사용 범위 |
|---|---|---|---|
| 2026-08-13 | samnet 공개 첫 화면 | https://www.samnet.kr/ | `CURRENT-PUBLIC`. 상·중·하순 날짜, 2D 전환 버튼, 황건적 토벌 진입점, 도시별 정세, 최근 공성 결과를 확인 |
| 2026-08-13 | samnet 공개 가입 화면 | https://www.samnet.kr/auth/register | `CURRENT-PUBLIC`. 지도 기반 시작 도시 선택, 도시 56개 목록, 국가별 성·인원 수를 확인. 제출하지 않음 |
| 2026-08-13 | samnet 전투 상세 예시 | https://www.samnet.kr/logs/battle/53 | `CURRENT-PUBLIC`. 비로그인 HTTP 200. `siege` 40턴과 턴별 `shiro/atk_sol/siege_dmg/wall_loss`, 최종 결과 payload를 확인. 피해 공식·RNG·서버 판정은 `UNKNOWN` |
| 2026-08-13 | 묘삼 공개 도움말 루트 | http://www.myosam.com/dokuwiki/doku.php?id=help:start | 비로그인 HTTP 200으로 현재 접근 가능. 콘텐츠는 오래된 도움말이므로 `HISTORICAL-PUBLIC`; 2026-06-27 조사 문서가 namespace 37페이지 전수 조사를 기록 |
| 2026-08-13 | 묘삼 운영 방향 Q&A | http://www.myosam.com/dokuwiki/doku.php?id=help:start:peq:peq | 비로그인 HTTP 200, 페이지 표시 최종 수정 2009-04-05. 도시지향 자기규정, 도시병사 부족/0의 공백지화, 국가 자원의 도시 이전 근거. `HISTORICAL-PUBLIC` |
| 2026-08-13 | 묘삼 시작·특징 | http://www.myosam.com/dokuwiki/doku.php?id=help:start:basic:myostart | 비로그인 HTTP 200, 페이지 표시 최종 수정 2009-09-12. 도시 단위 금·병량 근거. `HISTORICAL-PUBLIC` |
| 2026-08-13 | 과거 묘삼·samnet 조사 | `docs/superpowers/research/2026-06-27-v2-samnet-myosam-gap-design.md` | 과거 공개 관찰의 요약. 묘삼 원문을 대신하는 현행 1차 자료로 승격하지 않음 |
| 2026-08-13 | 과거 samnet 인증 관찰 | `docs/superpowers/research/2026-07-14-samnet-live-play-reverse-design.md` | 이번 공개-only 판정에서는 인증 후 관찰 내용을 증거로 쓰지 않음. 해당 범주는 `UNKNOWN` |
| 2026-08-13 | 현재 OpenSamguk | `logic/.../CommandRegistry.kt`, `logic/.../LogicEntities.kt`, `logic/.../ProcessIncome.kt`, `app/game-api/.../CommandWireMapper.kt`, `web/game/components/game/{GameChrome,MainControlBar}.tsx` | `REPO-OBSERVED`. v1 패러티 표면과 현재 UI/인테이크 구조 |
| 2026-08-13 | 현재 완료 원장 | `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md` | `REPO-OBSERVED`. 92/92 command inventory, 월간·전투·AI·부가 시스템의 비운영 패러티 종결 근거 |

### 관측 실패와 경계

- 최신 `origin/main`에는 과거 문서가 가리키는 `docs/wiki/raw/myosam-help/` 미러가 없다. 내장 브라우저의 cache fetch는 실패했지만 독립 비로그인 HTTP GET으로 위 묘삼 세 URL의 200 응답과 본문을 확인했다. 콘텐츠가 2009년 수정 도움말이므로 규칙은 `HISTORICAL-PUBLIC`, 현행 서비스 동작은 `UNKNOWN`이다.
- samnet의 `/manuals/*.html` 후보는 공개 브라우저에서 읽히지 않았다. 전투 상세는 내장 브라우저 cache miss 뒤 독립 비로그인 HTTP GET으로 복구했다. 로그인 뒤 메뉴·명령·국정·가챠에 관한 2026-07-14 기록은 이번 범위의 current evidence가 아니다.
- `sam.peppone.dev`는 공개 검색/브라우저로 관찰하지 못했다. “현재 OpenSamguk”은 배포 상태가 아니라 2026-08-13 `origin/main` 코드와 추적 문서를 뜻한다.

## 3. devsam/OpenSamguk 기준선

현재 OpenSamguk v1은 devsam 동작을 바꾸는 제품이 아니라 PHP grand truth를 Kotlin/Spring + Next.js로 옮긴 오리지널이다.

- 명령: 2026-07-26 감사는 PHP 93파일/92 고유 명령의 form→intake→daemon→flush→terminal **end-to-end audit inventory**를 비운영 PASS로 판정했다. 한 registry의 개수라는 뜻이 아니다. 예약 장수 작업은 `CommandRegistry.resolve()`, 즉시 작업은 `CommandWireMapper.intakeCodes`와 daemon dispatcher, join/bulk는 별도 경로를 소비한다. 따라서 새 capability는 어느 실행 경로에 속하는지 별도로 판정해야 한다.
- 도시: 현재 v1 `City`에는 상업·농업·치안·방어·성벽·인구·시세·지역 등이 있지만 도시 소유 금·병량·상주 도시병사는 없다. 금·병량은 `Nation`/`General` 소유다.
- 배치 효과: `ProcessIncome.IncomeNation.officerCntByCity`가 관직 2~4의 담당도시 배치를 수입에 반영한다. 묘삼식 “사람을 어디에 두는가”와 닮은 확장점은 있으나 묘삼의 임원진 효과를 구현했다는 뜻은 아니다.
- 시간/UI: `currentPhase`와 상·중·하순을 API·화면에 보존한다. `GameChrome`은 지도·예약턴·현재 장수/국가/도시·메시지를 한 작업대에 배치하고, `MainControlBar`는 20개 국가 메뉴를 역할별로 게이팅한다.
- v2: v1과 별도 DB/profile/route/Flyway로 격리하는 신규 제품이다. 국내 삼모 후보는 v1 패러티를 수정하지 않고 v2 전용 divergence로만 소비해야 한다.

## 4. 묘삼 조사

### 4.1 공개 도움말에서 확인된 묘삼 동작 — devsam 차분은 `DIFFERENTIAL-UNKNOWN`

아래는 2026-08-13에도 접근 가능한 2009년 수정 공개 도움말, 2026-06-27 도움말 37페이지 조사, 2026-07-25 독립 재대조 기록에 기반한 `HISTORICAL-PUBLIC` 관찰이다. 접근 가능성은 확인했지만 현행 런타임 동작이라는 뜻은 아니다. 또한 이 worktree에는 PHP `legacy/devsam-core`와 `hwe/ts` 오라클이 없으므로 devsam 대비 신규·변경·제거 분류는 모두 `DIFFERENTIAL-UNKNOWN`이다.

| 영역 | devsam 차분 분류 | 묘삼 공개 관찰 | OpenSamguk 현재 | 판정 |
|---|---|---|---|---|
| 경제 | `DIFFERENTIAL-UNKNOWN` | 국가가 다루던 금·병량을 도시 소유로 이동 | v1은 국가/장수 소유. v2 `OPENSAM-150~155`가 도시 원장·수입·병사·수송·조회로 계획됨 | 공개 제품 패턴. 이미 후속 티켓 존재; devsam 차분 근거로는 미사용 |
| 군사/도시 | `DIFFERENTIAL-UNKNOWN` | 도시마다 도시병사 상주, 병사 0이면 공백지화 | v1에는 도시병사 없음. v2 `OPENSAM-152~154`가 감소·보충·수송을 계획 | 공개 제품 패턴. 이미 후속 티켓 존재; devsam 차분 근거로는 미사용 |
| 인사/내정 | `DIFFERENTIAL-UNKNOWN` | 태수·군사 담당도시와 임원진 주둔이 도시 효과를 만든다 | v1은 관직 2~4 담당도시 수입 효과만 있음 | 제품 후보. PHP/Vue 대조 뒤에만 차분 분류 가능 |
| 도시 성장 | `DIFFERENTIAL-UNKNOWN` | 실제 도시 특색 8종(지도 표기 9개: `방어`는 `성벽`의 별칭, `없음(無)`은 무특색), 규모 5단계, 지역 7종 병종 게이트 | v1 `City.level/region`은 있으나 묘삼식 특색·발현 계약은 없음 | 제품 후보. PHP/Vue 대조와 콘텐츠/시설 중복 확인 필요 |
| 시설 | `DIFFERENTIAL-UNKNOWN` | 요새·성채·야전병원·진·궁노/연노·악대·투석대·목책/철책 | v1 도시 성벽/방어 수치와 전투는 있으나 설치물 체계 없음 | `OPENSAM-53`, `OPENSAM-171` 소비 후보; devsam 차분은 미확정 |
| 전투 | `DIFFERENTIAL-UNKNOWN` | 요격/야전 → 공성/농성 → 시가전과 시설 발동, 병력 일부 도시 복귀 | v1은 PHP 전투 패러티. v2 실시간 battle tickets `OPENSAM-157~174`가 별도 진행 예정 | phase 이름을 복제하지 말고 adapter 요구사항과 PHP/Vue 차분을 재검증 |
| 경제 정책 | `DIFFERENTIAL-UNKNOWN` | 도시 세율 5~35%, 지급률 100~500%, 도시 예산·수송 | v1 국가 단위 세율/지급률과 인접 이동이 있음 | 도시 원장 귀속 후 정책 범위와 PHP/Vue 차분을 함께 결정 |
| 조직/감시 | `DIFFERENTIAL-UNKNOWN` | 인사부·감찰부, 발령·호기·파견·집합, 포상·증여·헌납으로 사람 의존 형성 | v1에 관직/발령/포상/증여/헌납은 있으나 도시 권한·감시 묶음은 없음 | v2 capability 후보. 기존 명령과의 PHP/Vue 차분 확인 필요 |

묘삼에는 확인된 장수↔장수 관계망이 없었다. 묘삼식 “인맥”을 개인 친밀도 시스템으로 오독하면 안 된다. 공개 도움말이 보여준 사람 의존은 인사권·배치효과·감시·자원분배다. OpenSamguk v2의 의형제·원한·사제 관계망은 별도의 독자 시스템이며 묘삼 근거로 정당화할 수 없다.

### 4.2 공개 근거로 확인하지 못한 영역

- 현행 명령 전체 목록과 제거된 devsam 명령
- 외교 상태기계와 조약 수치
- NPC/AI 의사결정식과 RNG
- 계승·유산 포인트
- 현재 운영 여부, 현재 버전, 도움말 이후 변경

이 항목은 `UNKNOWN`이다. 과거 요약에 없는 수치나 동작을 추론해 후보로 만들지 않는다.

## 5. samnet.kr(칠랑섭) 조사

### 5.1 2026-08-13 로그인 없이 확인한 공개 표면

이 worktree에는 PHP `legacy/devsam-core`와 `hwe/ts` 오라클이 없었다. 따라서 아래 표는 제품 관찰과 OpenSamguk 현재 표면 비교이며, devsam 대비 시스템 신규/변경/제거 판정은 모두 `DIFFERENTIAL-UNKNOWN`이다. 후속 PHP source path:line 대조 전에는 제품 후보로만 쓴다.

| 공개 관찰 | devsam 차분 판정 | 안전한 제품 관찰 | 결론을 내릴 수 없는 것 |
|---|---|---|---|
| 첫 화면이 상·중·하순 날짜와 접속자 수를 표시 | `DIFFERENTIAL-UNKNOWN` | 접속 전에도 세계 시각과 활동도를 노출한다 | PHP/Vue 존재 여부, 내부 cadence·턴 실행식 |
| `2D모드` 전환 버튼과 지도 영역 | `DIFFERENTIAL-UNKNOWN` | 공개 shell에 2D/3D 전환 가능한 공간 지도가 있다 | PHP/Vue 존재 여부, 렌더링 구현, 지형 판정, picking 정확도 |
| 가입 화면의 지도 기반 시작 도시 선택과 56개 도시 목록 | `DIFFERENTIAL-UNKNOWN` | 계정 생성 전에 시작 위치를 지도로 선택한다 | PHP/Vue 존재 여부, 생성 뒤 소유권 효과, 밸런스 |
| 국가별 `성 수·인원 수`와 재야 선택 | `DIFFERENTIAL-UNKNOWN` | 가입 판단에 현재 세력 분포를 공개한다 | PHP/Vue 존재 여부, 가입 제한·보정·보호턴 계산 |
| 황건적 출몰 경고와 토벌 입장 버튼 | `DIFFERENTIAL-UNKNOWN` | 황건적 토벌이라는 공개 참여 진입점이 있다 | PHP/Vue 존재 여부, 이벤트 규칙, 보상, 전투식, NPC/AI |
| 최근 정세의 금광·풍작·재난·쌀 시세 | `DIFFERENTIAL-UNKNOWN` | 도시 묶음과 수치 효과를 공개 피드로 요약한다 | v1에도 재해·호황·도시 trade가 있어 기계적 차분 미확정 |
| 최근 전쟁의 공성, 공격 장수/부대, 도시 수비부대, 승/패/무 | `DIFFERENTIAL-UNKNOWN` | 로그인 전에도 전쟁 결과와 최소 참여 주체를 공개한다 | PHP/Vue 존재 여부, 피해식, 부대 편성 규칙, RNG |
| 전투 #53의 40턴 `siege` payload와 턴별 관측 필드 | `DIFFERENTIAL-UNKNOWN` | 공개 상세 replay schema와 관측 결과가 있다 | 피해 공식, RNG, authoritative 판정, 전체 battle type |

### 5.2 범주별 UNKNOWN

공개 첫 화면과 가입 화면만으로 다음을 판정할 수 없다.

- 명령 셋과 예약 슬롯 수
- 내정 명령·국가 정책·관직 권한
- 외교·서신·조약
- NPC/AI와 황건적 AI
- 계승·유산·장수 성장
- 전투 replay의 피해 공식·RNG·서버 판정
- 인증 후 UI의 일괄등록·프리셋·가챠

2026-07-14 저장소 문서는 인증 후 관찰을 담지만, 이번 티켓의 “공개 표면만” 계약에 따라 위 current 판정에 재사용하지 않았다.

## 6. 종합 관찰·차분 표

묘삼과 samnet의 제품 관찰은 유효하지만, 어느 쪽도 이 worktree에서 PHP/Vue source path:line 대조를 수행하지 않았으므로 devsam 차분 분류는 `DIFFERENTIAL-UNKNOWN`이다.

| 영역 | 묘삼 | samnet 공개 표면 | 현재 OpenSamguk | 후보 판정 |
|---|---|---|---|---|
| 명령 셋 | 세부 전체 목록 `UNKNOWN`; 발령·호기·파견·집합 등 도시 배치 강조 | `UNKNOWN` | v1 E2E audit 92/92; reserved/immediate/join·bulk 경로 분리 | 신규 명령보다 v2 capability/도시 귀속 재설계가 우선 |
| 내정 | 도시 금·병량·도시병사·도시 정책·특색·시설 | 명령은 `UNKNOWN`; 도시 사건 공개 피드만 확인 | v1 도시는 개발 수치, 자원은 국가/장수 | 도시 원장 계열은 채택·티켓화 완료 |
| 전투 | 요격·야전·공성·농성·시가전, 시설/장애물 | 공성 결과와 부대/도시 수비 주체만 공개 | v1 PHP 전투 패러티, v2 battle foundation 계획 | battle adapter 요구사항 후보. 수식 복제 금지 |
| 외교 | `UNKNOWN` | `UNKNOWN` | v1 외교 제안/수락/서신 배선 | 후보 없음 |
| 이벤트 | 도시병사 감소·공백지화 관련 규칙이 과거 도움말에 존재 | 황건적 토벌 진입점, 금광·풍작·재난·시세 피드 | v1 월간 재해/호황/시세, v2 공백지화 계획 | 황건적은 discovery spike만. 나머지는 신규 차분 미확정 |
| 경제 | 국가→도시 자원 이전, 도시 세율/지급률/예산/수송 | 도시별 금광·풍작·시세를 공개 | v1 국가 단위 자원/정책, v2 도시 원장 계획 | 도시 정책 scope는 후속 제품 결정 |
| NPC/AI | 일반 턴은 수행하나 고급 조직행동은 제한된다는 과거 도움말 요약 | `UNKNOWN` | v1 PHP AI 패러티, v2 별도 AI 미정 | 근거 부족. 신규 티켓 금지 |
| 계승·유산 | `UNKNOWN` | `UNKNOWN` | v1 유산·베팅·경매 구현 | 후보 없음 |
| UI | 공개 도움말 중심, 현행 런타임 UI `UNKNOWN` | 공개 2D/3D 지도, 시작도시 picker, 세계/전쟁/사건 feed·40턴 전투 payload | 지도·예약턴·상태·20메뉴 작업대, v2 3D/UI 이슈 존재 | 제품 패턴 후보. devsam 차분은 PHP/Vue 대조 전 `UNKNOWN` |

## 7. 종합 티켓 소비용 후보 목록

우선순위는 “공개 증거 강도 × OpenSamguk 차별 가치 ÷ 중복·패러티 위험”으로 정했다.

| ID | 후보 | 출처/증거 | 상태 | 소비처/다음 행동 | 주요 위험 |
|---|---|---|---|---|---|
| `DOM-01` | 도시 소유 금·병량 + 도시병사 원장 | 묘삼 `HISTORICAL-PUBLIC` 동작 관찰 재대조 완료; devsam 차분 `DIFFERENTIAL-UNKNOWN` | `ADOPTED-ALREADY-TICKETED` | `OPENSAM-150~155`에 provenance 링크만 추가. 새 티켓 금지 | v1 `Nation.gold/rice`와 이중 진실, flush/rehydrate |
| `DOM-02` | 관직자/임원진의 담당도시 체류 효과 | 묘삼 `HISTORICAL-PUBLIC`; v1 `officerCntByCity` 확장점 | `CANDIDATE` | 아래 Draft A | 효과 중첩 폭증, 최적 배치 고착, v1 패러티 침범 |
| `DOM-03` | 도시 시설·장애물과 전장 phase 연계 | 묘삼 `HISTORICAL-PUBLIC` | `CANDIDATE-DUPLICATE-CHECK` | `OPENSAM-53`, `OPENSAM-171` 요구사항에 evidence link. 새 티켓은 gap 확인 후 | 시설 목록 복제, adapter 간 규칙 불일치 |
| `DOM-04` | 로그인 전 세계 사건·전쟁 요약 feed와 공개 전투 payload | samnet `CURRENT-PUBLIC`; devsam 차분 `UNKNOWN` | `PRODUCT-CANDIDATE` | 아래 Draft B. `OPENSAM-113`에는 귀속하지 않음 | 정보전 fog/기밀 유출, 캐시·개인정보 |
| `DOM-05` | 지도 기반 시작 도시 선택 + 세력 분포 | samnet `CURRENT-PUBLIC` | `CANDIDATE-DEFER` | 아래 Draft C. v2 가입/possession 모델 확정 뒤 | 신규 유저 쏠림, 다계정/스파이, 빈 도시 선택 |
| `DOM-06` | 황건적 토벌 공개 이벤트 | samnet `CURRENT-PUBLIC` 진입점만 | `DISCOVERY-ONLY` | 아래 Draft D. 규칙을 얻기 전 구현 금지 | 이벤트 규칙·보상·AI 전부 `UNKNOWN` |
| `DOM-07` | 2D/3D 지도 전환과 공간 선택 | samnet `CURRENT-PUBLIC` UI | `ADOPTED-ALREADY-TICKETED` | `OPENSAM-41`, `OPENSAM-173`에 provenance link | 3D 장식화, 2D/3D 판정 이중화 |
| `DOM-08` | 도시 시세·금광·풍작·재난 공개 | samnet `CURRENT-PUBLIC` | `NO-DIFFERENTIAL-YET` | v1 이벤트와 의미 비교 전 신규 티켓 금지 | 기존 devsam 기능을 신규로 오인 |
| `DOM-09` | 명령 큐·일괄등록·프리셋·가챠 | 이번 공개-only 조사에서 `UNKNOWN` | `HOLD` | 공개 매뉴얼이 관찰되거나 별도 권한 승인 전 보류 | 인증 관찰을 공개 증거로 위장 |

## 8. 후속 티켓 초안

아래는 외부 tracker에 아직 쓰지 않는 draft다.

### Draft A — `[V2-CITY] 관직자 체류 효과 카탈로그와 담당도시 한정 판정`

목표: 기존 `officer_level`과 담당도시를 평행한 새 조직 축 없이 v2 도시 원장에 연결한다.

- 처분: **new ticket draft**. 기존 `OPENSAM-150/151/155` 본문 수정이 아니다.
- 소유 경계: v2 logic의 순수 도시 효과 calculator와 월간 수입 consumer만 소유한다. 도시 원장 schema/flush는 `OPENSAM-150`, 생산 수입은 `OPENSAM-151`, read projection은 `OPENSAM-155` 소유이므로 이 draft가 co-widen하지 않는다.
- 시작 조건: `OPENSAM-150`, `OPENSAM-151`, `OPENSAM-155`가 merge되고 해당 entity/API가 고정된 뒤.

- Given 관직자가 담당도시와 실제 체류도시를 가진다.
- When 월간 도시 수입/치안/보급 leaf가 실행된다.
- Then 승인된 효과 카탈로그만 담당도시에 적용되고, `effectId/sourceGeneralId/cityId/before/after`가 deterministic log/replay에 남는다.
- 중첩 순서와 상한을 고정하고 장수 ID 오름차순으로 fold한다.
- v1 `ProcessIncome`, PHP golden, RNG draw는 byte-inert임을 canonical diff와 backend gate로 증명한다.
- 선행: `OPENSAM-150`, `OPENSAM-151`, `OPENSAM-155`.
- 조사 필요: 묘삼 임원진 6종을 그대로 채택할지, 이름 없이 역할군으로 재설계할지 제품 결정.
- 첫 검증: 신규 calculator 단위 테스트 + v1 canonical diff + `tools/parity/gate.sh backend`.

### Draft B — `[V2-OBS] 공개 세계 사건·전쟁 요약 projection`

목표: 로그인 전에도 세계가 살아 있음을 보여주되 fog/기밀/개인정보를 노출하지 않는다.

- 처분: **new ticket draft**. 인증된 인게임 UI 소유인 `OPENSAM-113`의 amendment가 아니다.
- 소유 경계: v2의 unauthenticated public read DTO/endpoint와 gateway 공개 consumer. battle/event 생산자, durable schema, 인증된 game UI는 비범위다.
- 시작 조건: v2 world event/battle durable producer와 public route/profile 소유 티켓이 식별·merge된 뒤. 현재 그 issue ID는 `UNKNOWN`이므로 이 draft는 아직 startable하지 않다.

- Given durable world event/battle result가 commit됐다.
- When 공개 projection이 최근 N건을 조회한다.
- Then allowlist된 날짜·사건 종류·도시/세력·최종 결과만 반환하고 명령·좌표·미공개 병력·account identity는 제외한다.
- 동일 committed version 전에는 UI가 사건을 먼저 표시하지 않는다.
- empty world, stale cache, deleted world, on-demand v1 closed 상태를 각각 명시적으로 렌더한다.
- samnet UI/문구/자산은 복제하지 않는다.
- 첫 검증: unauthenticated contract test, 비밀 필드 denylist/allowlist test, public consumer 빈/stale 상태 테스트.

### Draft C — `[V2-ONBOARD] 지도 기반 시작 위치 선택과 세력 혼잡도 안내`

목표: 가입/장수 생성 전에 서버가 허용한 시작 위치와 혼잡도를 동일 후보 집합으로 지도·목록에 보여준다.

- 처분: **HOLD / future new ticket draft**. v2 account/profile/possession foundation의 issue ID가 아직 `UNKNOWN`이므로 tracker 발행·착수 금지.
- 소유 경계: foundation이 제공하는 후보 조회/precheck 계약의 gateway consumer만 소유한다. 계정 생성, possession schema, 국가 균형 정책, `OPENSAM-41` spatial engine은 비범위다.
- 시작 조건: v2 account/profile/possession foundation issue가 생성·merge되고 `OPENSAM-41`의 candidate ID picking 계약이 고정된 뒤.

- Given 서버가 `candidateId/allowed/reason/populationBand`를 반환한다.
- When 사용자가 지도 또는 대체 목록에서 후보를 고른다.
- Then 두 surface가 같은 candidate ID를 사용하고 최종 생성 전 서버가 다시 precheck한다.
- 정확한 활성 인원 대신 버킷화된 혼잡도를 노출해 스파이·개인 추적을 줄인다.
- 자동 균형 보정, 시작 보상, 국가 잠금은 별도 제품 결정이며 이 티켓에서 추정하지 않는다.
- 선행: v2 account/profile/possession 계약과 `OPENSAM-41` spatial picking.
- 첫 검증: 지도/대체 목록 candidate ID 동일성 테스트 + submit 직전 서버 precheck contract test.

### Draft D — `[SPIKE] 황건적 토벌 공개 이벤트 계약 조사`

목표: 공개 진입점 하나를 보고 규칙을 창작하지 않고, 구현 티켓 작성에 필요한 최소 계약을 확보한다.

- 조사 항목: 입장 조건, lifecycle, 전투 주체, 승패, 보상/손실, 반복 주기, NPC/AI, replay 공개 범위.
- 공개 공지/매뉴얼만 사용한다. 계정 필요 항목은 `UNKNOWN`으로 남긴다.
- 산출물은 채택/기각/보류 판정과 근거 URL이며 구현 코드는 없다.
- 최소 5개 핵심 항목 중 하나라도 `UNKNOWN`이면 구현 티켓으로 승격하지 않는다.

## 9. 패러티·제품 위험과 결정

1. **v1에 이식하지 않는다.** v1은 devsam 오리지널 패러티다. 모든 시스템 후보는 v2 profile/DB/route/Flyway 아래의 sanctioned divergence여야 한다.
2. **상위 Epic #250의 M-config 문구는 현재 결정과 맞지 않는다.** ADR-LITE-018은 v1 `GameConst`/PHP golden을 그대로 동결하고 v2를 별도 제품으로 분리한다. 따라서 “frozen-baseline 갱신 뒤 v1 시스템 변경”을 후속 티켓의 전제로 복사하지 말고, v2 격리 게이트를 전제로 써야 한다.
3. **보이는 UI와 실제 규칙을 분리한다.** samnet의 버튼·피드·전쟁 라벨은 mechanics 증거가 아니다. 수치·RNG·AI를 추론하지 않는다.
4. **기존 티켓과 중복 생성하지 않는다.** 도시 원장, 3D, 시설, battle replay는 이미 열린 consumer가 있다. 조사 결과는 새 epic보다 해당 티켓의 provenance와 AC gap으로 먼저 소비한다.
5. **명칭·문구·자산을 복제하지 않는다.** 시스템 패턴만 요약하고 OpenSamguk 도메인 언어와 접근성/보안 계약으로 재설계한다.
6. **현행성 한계를 보존한다.** 묘삼은 historical evidence, samnet은 unauthenticated shell evidence, OpenSamguk은 repository evidence다. 세 종류를 같은 확신도로 합치지 않는다.

## 10. 결론

공개 증거로 가장 강한 국내 삼모 제품 관찰은 묘삼의 **도시 자원·도시병사·사람 배치 효과**다. samnet에서는 **공개 world observability·지도 기반 온보딩·공개 전투 payload**라는 제품 패턴을 확인했다. 그러나 PHP/Vue 오라클 부재로 두 서버 모두 devsam 차분은 `DIFFERENTIAL-UNKNOWN`이다. 도시 원장 핵심은 이미 별도 제품 결정에 따라 `OPENSAM-150~155`에 흡수됐으므로, 이 문서를 devsam 차분 근거로 사용하지 않고 provenance와 중복 방지에만 쓴다. 새로 검토할 만한 것은 PHP/Vue 대조 뒤의 담당도시 체류 효과와, 선행 producer/route 티켓이 식별된 뒤의 공개 projection이다.

반대로 samnet의 명령 셋·내정·외교·NPC/AI·계승은 이번 공개-only 조사로 확인하지 못했다. 전투 상세는 40턴 payload와 관측 값까지만 확인했으며 피해 공식·RNG·authoritative 판정은 `UNKNOWN`이다. 과거 인증 관찰이나 UI 단서로 빈칸을 채우지 않는다.
