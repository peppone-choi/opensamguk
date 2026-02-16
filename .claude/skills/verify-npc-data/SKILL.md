---
name: verify-npc-data
description: 시나리오 NPC 장수 데이터가 삼국지14 기준 5-stat(통무지정매)으로 최신화되었는지 확인합니다. 시나리오/장수 데이터 수정 후 사용.
---

# NPC Data 검증

## Purpose

시나리오 NPC 장수 데이터가 삼국지14 무장 정보 기준으로 최신화되었는지 검증합니다:

1. **5-stat 완성** — 레거시 3-stat(통솔/무력/지력) → 5-stat(통솔/무력/지력/정치/매력) 마이그레이션 확인
2. **삼국지14 기준 대조** — NPC 장수의 스탯이 삼국지14 공식 데이터와 일치하는지 확인
3. **누락 장수** — 삼국지14에 존재하지만 시나리오에 없는 장수 식별
4. **이름 정합** — 장수 이름이 삼국지14 한국어 표기와 일치하는지 확인
5. **시대별 정합** — 장수의 생년/몰년이 삼국지14 데이터와 일치하는지 확인
6. **비역사 NPC 공식** — 삼국지14에 없는 가상/비역사 NPC의 정치/매력이 공식에 의해 생성되었는지 확인

## When to Run

- 시나리오 JSON의 장수 데이터를 수정한 후
- 새로운 시나리오를 추가한 후
- 3-stat → 5-stat 마이그레이션을 수행한 후
- 삼국지14 데이터 기반으로 장수 스탯을 갱신한 후
- NPC 장수 데이터의 전체 정합성을 점검할 때

## Data Sources

삼국지14 무장 정보는 다음 소스에서 확인합니다:

1. **로컬 xlsx** — `삼국지14 무장정보.xlsx` (프로젝트 루트)
2. **온라인 위키** — https://wikiwiki.jp/sangokushi14/史実武将 (기본), https://wikiwiki.jp/sangokushi14/史実武将(PK) (PK 추가 무장)

xlsx 파일이 최우선 소스이며, 온라인 위키는 보조/검증용입니다.

## Stat Mapping

| 한글 | 영문 필드    | 레거시 | 삼국지14 | 새 프로젝트 |
| ---- | ------------ | ------ | -------- | ----------- |
| 통솔 | `leadership` | O      | O        | O           |
| 무력 | `strength`   | O      | O        | O           |
| 지력 | `intel`      | O      | O        | O           |
| 정치 | `politics`   | X      | O        | O           |
| 매력 | `charm`      | X      | O        | O           |

**핵심:** 레거시 시나리오 JSON은 `[affinity, name, picture, nationID, city, leadership, strength, intel, officerLevel, birth, death, ego, special, text]` 튜플 형식으로 3-stat만 포함. 새 프로젝트는 `politics`와 `charm`을 추가하여 5-stat 튜플로 확장해야 함.

## Related Files

| File                                           | Purpose                                |
| ---------------------------------------------- | -------------------------------------- |
| `삼국지14 무장정보.xlsx`                       | 삼국지14 무장 스탯 원본 데이터         |
| `legacy/hwe/scenario/scenario_*.json`          | 레거시 시나리오 장수 데이터 (3-stat)   |
| `legacy/hwe/scenario/frame.json`               | 시나리오 프레임/기본 구조              |
| `legacy/hwe/sammo/Scenario/GeneralBuilder.php` | 레거시 장수 빌더 (3-stat)              |
| `legacy/hwe/sammo/Scenario.php`                | 레거시 시나리오 로더                   |
| `docs/architecture/legacy-scenarios.md`        | 시나리오 시스템 문서                   |
| `backend/src/main/resources/data/scenarios/`   | 새 시나리오 데이터 (구현 대상, 5-stat) |

## Workflow

### Step 1: 새 프로젝트 시나리오 데이터 형식 확인

**검사:** 새 프로젝트의 시나리오 장수 데이터가 5-stat 형식인지 확인합니다.

```bash
# 새 프로젝트 시나리오 파일 존재 확인
ls backend/src/main/resources/data/scenarios/ 2>/dev/null

# 장수 데이터에서 politics/charm 필드 존재 확인
grep -rn "politics\|charm" backend/src/main/resources/data/scenarios/ 2>/dev/null
```

**PASS:** 시나리오 장수 데이터에 5개 스탯(leadership, strength, intel, politics, charm) 모두 포함
**FAIL:** politics 또는 charm이 누락됨 — 3-stat에서 마이그레이션 필요

### Step 2: 레거시 시나리오 3-stat 현황 파악

**파일:** `legacy/hwe/scenario/scenario_*.json`

**검사:** 레거시 시나리오의 장수 수와 3-stat 구조를 파악합니다.

```bash
# 레거시 시나리오 수
ls legacy/hwe/scenario/scenario_*.json | wc -l

# 샘플 장수 데이터 (첫 시나리오의 general 배열)
python3 -c "
import json
with open('legacy/hwe/scenario/scenario_1010.json') as f:
    data = json.load(f)
generals = data.get('general', [])
print(f'장수 수: {len(generals)}')
if generals:
    g = generals[0]
    print(f'첫 장수: {g[1]}, 통={g[5]}, 무={g[6]}, 지={g[7]}')
    print(f'튜플 길이: {len(g)}')
" 2>/dev/null
```

**기록:** 레거시 튜플 인덱스 매핑:

- `[0]` affinity, `[1]` name, `[2]` picture, `[3]` nationID
- `[4]` city, `[5]` leadership, `[6]` strength, `[7]` intel
- `[8]` officerLevel, `[9]` birth, `[10]` death
- `[11]` ego, `[12]` special, `[13]` text (optional)

### Step 3: 삼국지14 데이터 대조

**검사:** 시나리오 NPC 장수의 스탯이 삼국지14 기준과 일치하는지 확인합니다.

**xlsx 사용 시:**

```bash
# xlsx에서 장수 데이터 추출 (python3 + openpyxl)
python3 -c "
import openpyxl
wb = openpyxl.load_workbook('삼국지14 무장정보.xlsx', read_only=True)
ws = wb.active
headers = [cell.value for cell in next(ws.iter_rows(min_row=1, max_row=1))]
print('컬럼:', headers[:10])
for row in ws.iter_rows(min_row=2, max_row=6, values_only=True):
    print(row[:10])
" 2>/dev/null || echo "openpyxl 미설치 — pip install openpyxl 필요"
```

**온라인 위키 사용 시:**

- https://wikiwiki.jp/sangokushi14/史実武将 에서 장수별 통솔/무력/지력/정치/매력 확인
- https://wikiwiki.jp/sangokushi14/史実武将(PK) 에서 PK 추가 무장 확인

**비교 방법:**

1. 시나리오 장수 이름으로 삼국지14 데이터 검색
2. 통솔/무력/지력 3개 스탯이 삼국지14와 일치하는지 확인
3. 정치/매력 2개 스탯이 삼국지14에서 추출되어 추가되었는지 확인

**PASS:** 장수 스탯이 삼국지14와 일치 (±5 범위 허용)
**FAIL:** 스탯 불일치 또는 정치/매력 미추가

### Step 4: 누락 장수 식별

**검사:** 삼국지14에 존재하지만 시나리오에 없는 주요 장수를 식별합니다.

```bash
# 시나리오별 장수 이름 추출
python3 -c "
import json, glob
names = set()
for f in glob.glob('legacy/hwe/scenario/scenario_*.json'):
    with open(f) as fh:
        data = json.load(fh)
    for g in data.get('general', []) + data.get('general_ex', []) + data.get('general_neutral', []):
        names.add(g[1])
print(f'고유 장수 수: {len(names)}')
" 2>/dev/null
```

**PASS:** 삼국지14 주요 장수가 시나리오에 포함됨
**FAIL:** 누락된 주요 장수 발견

### Step 5: 이름/생몰년 정합성

**검사:** 장수 이름이 삼국지14 한국어 표기와 일치하고, 생년/몰년이 정확한지 확인합니다.

```bash
# 이름 및 생몰년 샘플 추출
python3 -c "
import json
with open('legacy/hwe/scenario/scenario_1010.json') as f:
    data = json.load(f)
for g in data.get('general', [])[:10]:
    print(f'{g[1]}: 통={g[5]} 무={g[6]} 지={g[7]} 생={g[9]} 몰={g[10]}')
" 2>/dev/null
```

**PASS:** 이름/생몰년이 삼국지14와 일치
**FAIL:** 표기 차이 또는 생몰년 불일치 발견

### Step 6: 비역사 NPC 정치/매력 공식 검증

**검사:** 삼국지14에 존재하지 않는 가상/비역사 NPC 장수의 정치(politics)와 매력(charm) 스탯이 정의된 공식에 의해 생성되었는지 확인합니다.

**배경:** 레거시 시나리오에는 삼국지14에 없는 가상 NPC(오랑캐, 이벤트 전용 장수 등)가 존재합니다. 이들은 삼국지14 대조가 불가능하므로, 기존 3-stat(통솔/무력/지력)을 기반으로 정치/매력을 생성하는 공식이 필요합니다.

**공식 요구사항:**

- 입력: 기존 3-stat (leadership, strength, intel) + 장수 특성(ego, special 등)
- 출력: politics (0-100), charm (0-100)
- 결정적: 같은 입력 → 같은 출력 (랜덤 요소 없음)
- 합리적 분포: 극단값(0, 100) 편중 없이 자연스러운 분포

```bash
# 비역사 NPC 공식 관련 코드 검색
grep -rn "generatePolitics\|generateCharm\|calcPolitics\|calcCharm\|formulaPolitics\|formulaCharm\|nonHistorical\|virtualGeneral" backend/src/main/kotlin/com/opensam/ 2>/dev/null

# 가상 장수 식별 로직 검색
grep -rn "isHistorical\|isVirtual\|isFictional\|삼국지14" backend/src/main/kotlin/com/opensam/ 2>/dev/null
```

**검증 항목:**

1. 공식 함수가 존재하는지 확인
2. 공식이 결정적인지 확인 (같은 입력 → 같은 출력)
3. 삼국지14 대조 불가 장수에 대해 공식이 적용되었는지 확인
4. 생성된 politics/charm 값이 0-100 범위이고 합리적 분포인지 확인

**PASS:** 비역사 NPC용 정치/매력 생성 공식이 존재하고 올바르게 적용됨
**FAIL:** 공식 미구현 또는 비역사 NPC에 정치/매력이 누락됨

### Step 7: 5-stat 마이그레이션 진행률 보고

모든 검사 결과를 종합하여 마이그레이션 상태를 보고합니다.

## Output Format

```markdown
## NPC Data 검증 결과

### 5-stat 마이그레이션 상태

| 항목                    | 상태      | 상세                      |
| ----------------------- | --------- | ------------------------- |
| 새 시나리오 5-stat 형식 | PASS/FAIL | X개 시나리오 중 Y개 완료  |
| politics 필드 추가      | PASS/FAIL | X명 장수 중 Y명 추가 완료 |
| charm 필드 추가         | PASS/FAIL | X명 장수 중 Y명 추가 완료 |

### 삼국지14 대조 결과

| #   | 장수 이름 | 통  | 무  | 지  | 정  | 매  | 삼14 일치 | 비고      |
| --- | --------- | --- | --- | --- | --- | --- | --------- | --------- |
| 1   | 조조      | 72  | 72  | 91  | 91  | 96  | PASS      |           |
| 2   | 유비      | 75  | 66  | 65  | 78  | 99  | FAIL      | 지력 차이 |

### 비역사 NPC 공식 적용

| 항목               | 상태      | 상세                            |
| ------------------ | --------- | ------------------------------- |
| 공식 함수 존재     | PASS/FAIL | 함수명/위치                     |
| 결정적 출력        | PASS/FAIL | 같은 입력 → 같은 출력 확인      |
| 적용 대상 커버리지 | PASS/FAIL | X명 비역사 NPC 중 Y명 적용 완료 |

### 요약

- 고유 장수 수: N명
- 삼국지14 일치: X명 (X%)
- 스탯 불일치: Y명
- politics/charm 미추가: Z명
- 비역사 NPC 공식 적용: W명
```

## Exceptions

다음은 **위반이 아닙니다**:

1. **레거시 시나리오의 3-stat** — 레거시 JSON은 원본 참조용; 3-stat이 정상. 새 프로젝트 시나리오만 5-stat 필수
2. **스탯 미세 차이 (±5)** — 삼국지14 기본판과 PK판 간 스탯이 다를 수 있고, 게임 밸런스 조정이 있을 수 있음; ±5 이내 차이는 허용
3. **가상 장수의 삼국지14 대조 면제** — 삼국지14에 없는 장수(오랑캐, 가상 NPC)는 삼국지14 스탯 대조 대상에서 제외; 단, 5-stat 완성은 필수이며 정치/매력은 공식에 의해 생성되어야 함
4. **시나리오별 스탯 변동** — 삼국지14도 시나리오별로 장수 스탯이 변할 수 있음; 기본값 기준 대조
5. **xlsx 미존재** — xlsx 파일이 없는 경우 온라인 위키만으로 검증 가능; xlsx 부재는 에러가 아님
6. **openpyxl 미설치** — Python 패키지 미설치 시 수동 검증으로 대체; 에러가 아닌 안내로 보고
7. **neutral/ex 장수** — `general_neutral`, `general_ex` 배열의 장수도 동일 기준 적용하되, 이 배열이 비어있는 것은 정상
