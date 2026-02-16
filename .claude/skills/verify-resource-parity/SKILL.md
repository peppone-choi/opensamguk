---
name: verify-resource-parity
description: 레거시 PHP에서 추출된 게임 리소스(시나리오, 맵, 도시, 장수, 관직 등)가 새 프로젝트에 존재하는지 확인합니다. 리소스 데이터 추가/수정 후 사용.
---

# Resource Parity 검증

## Purpose

레거시 PHP/core2026에서 추출된 게임 정적 리소스가 새 프로젝트에 존재하고 올바른지 검증합니다:

1. **시나리오 데이터** — 레거시 24종 시나리오 JSON이 새 프로젝트에 존재하는지 확인
2. **맵 데이터** — 8종 맵 데이터가 존재하는지 확인
3. **도시 상수** — 100+ 도시 정의(CityConstBase)가 game_const.json에 반영되어 있는지 확인
4. **관직 데이터** — officer_ranks.json이 레거시 관직 체계와 일치하는지 확인
5. **유닛/병종 데이터** — GameUnitConst 병종 정의가 존재하는지 확인

## When to Run

- 시나리오/맵/도시 데이터를 추가하거나 수정한 후
- `backend/src/main/resources/data/` 하위 파일을 변경한 후
- 레거시 PHP에서 새로운 정적 데이터를 포팅한 후
- 리소스 로더/파서를 구현한 후
- 마일스톤 리소스 완성도를 점검할 때

## Related Files

| File                                                 | Purpose                                 |
| ---------------------------------------------------- | --------------------------------------- |
| `legacy/hwe/sammo/Scenario.php`                      | 레거시 시나리오 로더                    |
| `legacy/hwe/sammo/CityConstBase.php`                 | 레거시 도시 상수 (100+ 도시)            |
| `legacy/hwe/sammo/GameConstBase.php`                 | 레거시 게임 상수                        |
| `legacy/hwe/sammo/GameUnitConstBase.php`             | 레거시 병종 상수                        |
| `legacy/hwe/sammo/GeneralBuilder.php`                | 레거시 장수 빌더 (NPC 생성)             |
| `legacy/hwe/sammo/Nation.php`                        | 레거시 국가 빌더                        |
| `docs/architecture/legacy-scenarios.md`              | 시나리오 시스템 문서                    |
| `docs/architecture/legacy-engine-constants.md`       | 상수/유닛 문서                          |
| `docs/architecture/legacy-engine-city.md`            | 도시 엔진 문서                          |
| `backend/src/main/resources/data/game_const.json`    | 도시 레벨/지역 매핑, 초기값 (구현 대상) |
| `backend/src/main/resources/data/officer_ranks.json` | 관직 체계 (구현 대상)                   |
| `backend/src/main/resources/data/maps/`              | 맵 데이터 (구현 대상)                   |

## Workflow

### Step 1: 시나리오 데이터 존재 확인

**파일:** `backend/src/main/resources/data/scenarios/` 또는 동등 경로

**검사:** 레거시 24종 시나리오에 대응하는 데이터가 존재하는지 확인합니다.

```bash
# 새 프로젝트 시나리오 파일 목록
ls backend/src/main/resources/data/scenarios/ 2>/dev/null | wc -l

# 레거시 시나리오 참조 (Scenario.php에서 시나리오 ID 추출)
grep -n "scenario_\|getAllScenarios\|scenarioList" legacy/hwe/sammo/Scenario.php | head -20
```

**PASS:** 최소 1개 이상의 시나리오 데이터 파일이 존재하고, 레거시 시나리오와 대응 관계가 확인됨
**FAIL:** 시나리오 데이터 디렉토리가 없거나 비어있음

### Step 2: 맵 데이터 존재 확인

**파일:** `backend/src/main/resources/data/maps/`

**검사:** 8종 맵 데이터가 존재하는지 확인합니다.

```bash
ls backend/src/main/resources/data/maps/ 2>/dev/null | sort
```

레거시 맵 목록: che, miniche, cr, 및 기타 (legacy/scenario/map/\*.php 참조)

**PASS:** 맵 데이터 파일이 존재
**FAIL:** 맵 데이터 디렉토리가 없거나 누락된 맵이 있음

### Step 3: 도시 상수 존재 확인

**파일:** `backend/src/main/resources/data/game_const.json`

**검사:** game_const.json에 도시 정의가 포함되어 있고, 레거시 CityConstBase의 도시 수와 일치하는지 확인합니다.

```bash
# game_const.json 존재 및 도시 수 확인
if [ -f backend/src/main/resources/data/game_const.json ]; then
  echo "PASS: game_const.json exists"
  # 도시 항목 수 대략 확인
  grep -c '"city":\|"cityId":\|"name":' backend/src/main/resources/data/game_const.json 2>/dev/null || echo "구조 확인 필요"
else
  echo "FAIL: game_const.json missing"
fi
```

**추가 검사:** 레거시 도시 수와 비교합니다.

```bash
# 레거시 CityConstBase에서 도시 수 추출
grep -c "new CityInitialDetail\|'region'" legacy/hwe/sammo/CityConstBase.php 2>/dev/null
```

**PASS:** game_const.json의 도시 수가 레거시와 일치 (100+ 도시)
**FAIL:** 도시 수 불일치 또는 game_const.json 자체가 없음

### Step 4: 관직 데이터 확인

**파일:** `backend/src/main/resources/data/officer_ranks.json`

**검사:** 관직 데이터가 존재하고, CLAUDE.md에 정의된 관직 체계(국가 레벨 0~7)를 포함하는지 확인합니다.

```bash
if [ -f backend/src/main/resources/data/officer_ranks.json ]; then
  echo "PASS: officer_ranks.json exists"
  # 국가 레벨별 관직 존재 확인
  grep -c '"level":\|"rank":\|"승상\|태위\|사도\|사공"' backend/src/main/resources/data/officer_ranks.json 2>/dev/null || echo "구조 확인 필요"
else
  echo "FAIL: officer_ranks.json missing"
fi
```

**PASS:** officer_ranks.json이 존재하고 국가 레벨 0~7에 대한 관직이 정의됨
**FAIL:** 파일 누락 또는 관직 체계 불완전

### Step 5: 유닛/병종 데이터 확인

**검사:** 레거시 GameUnitConstBase에 정의된 병종이 새 프로젝트에 존재하는지 확인합니다.

```bash
# 새 프로젝트에서 병종 관련 데이터 검색
grep -rn "crewType\|unitType\|병종\|GameUnit" backend/src/main/resources/data/ 2>/dev/null
grep -rn "crewType\|unitType" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**PASS:** 병종 데이터가 Kotlin enum이나 JSON으로 정의됨
**FAIL:** 병종 데이터 없음

### Step 6: 리소스 패러티 보고서

모든 검사 결과를 종합합니다.

## Output Format

```markdown
## Resource Parity 검증 결과

| #   | 리소스   | 레거시 수 | 현재 수 | 상태      | 비고 |
| --- | -------- | --------- | ------- | --------- | ---- |
| 1   | 시나리오 | 24        | X       | PASS/FAIL | ...  |
| 2   | 맵       | 8         | X       | PASS/FAIL | ...  |
| 3   | 도시     | 100+      | X       | PASS/FAIL | ...  |
| 4   | 관직     | 7 레벨    | X       | PASS/FAIL | ...  |
| 5   | 병종     | N         | X       | PASS/FAIL | ...  |
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **점진적 포팅** — 시나리오 24종 중 일부만 먼저 구현하는 것은 정상; 존재하는 시나리오 수를 보고하되 FAIL 처리하지 않음
2. **데이터 형식 차이** — 레거시 PHP 배열 → JSON 변환 시 키 이름이 다를 수 있음 (예: snake_case → camelCase); 값이 동일하면 정합으로 간주
3. **시나리오별 맵** — 모든 맵이 독립 파일이 아닌 시나리오 JSON에 내장될 수 있음; 맵 데이터가 어딘가에 존재하면 PASS
4. **game_const.json 구조** — 레거시의 PHP 클래스 구조와 JSON 구조는 1:1 매핑이 아닐 수 있음; 핵심 도시 데이터(이름, 지역, 레벨)가 포함되면 PASS
5. **황건 특수 관직** — 황건 관직이 별도 파일이거나 officer_ranks.json 내 특수 섹션인 것 모두 허용
