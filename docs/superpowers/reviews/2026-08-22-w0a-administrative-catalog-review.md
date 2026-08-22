# W0-A 후한 군국지 정본 독립 검토

Scope: tools/
Verdict: cleared

## 범위

- `tools/map/junguozhi_contract.py`
- `tools/map/audit_junguozhi_source.py`
- `tools/map/tests/test_junguozhi_contract.py`
- `data/curated/han/administrative-units.json`

검토자는 `fable-deep-reasoner` 역할의 별도 read-only agent였다. 구현자는 검토 중 발견된
문제를 수정했고, 검토자는 최신 snapshot에서 다시 실행한 뒤 최종 판정을 내렸다.

## 발견과 해소

1. **MAJOR — 위치 zip이 source heading 변조를 숨김.** corpus 권109–113 snapshot hash,
   raw heading 105개 exact sequence, heading/unit mutation test를 추가해 fail-closed로 바꿨다.
2. **MAJOR — `canonicalName`이 ctext에서 오지 않았는데 번체 정본처럼 보임.** 필드를
   제거했다. `sourceName`은 corpus literal이고, 독립 근거가 있는 경우만 `nameCorrection`을
   둔다. ctext는 실제 HTML snapshot을 읽어 105개 번체 군국 heading과 上郡 블록의
   `龜茲屬國` 배치만 증언한다.
3. **MAJOR — committed JSON drift가 unit test에서 빠짐.** generator output과 추적 artifact의
   byte-exact 동등성 테스트를 추가했다.
4. **MAJOR — nested MediaWiki markup 두 건 누출.** 반복 unwrap과 누출 0 단언을 추가했다.
5. **MAJOR — source 손상 placeholder 세 건이 지명처럼 보임.** `参[�]`, `朴[B459]`,
   `朱[B42B]`을 `SOURCE_PLACEHOLDER` / `UNRESOLVED_SOURCE_PLACEHOLDER`로 격리하고
   ctext witness text·행·snapshot hash를 기록했다. 정식 이름으로 자동 교정하지 않았다.
6. **MINOR — 龜茲 정정이 요약과 citation만 포함.** 《後漢書》 권65의 직접 인용
   `龜茲音丘慈，縣名，屬上郡。`과 snapshot hash를 artifact에 넣었다.

## 최종 증거

- `python3 -m unittest tools/map/tests/test_junguozhi_contract.py`: 9/9 통과.
- `python3 tools/map/audit_junguozhi_source.py --check data/curated/han/administrative-units.json`:
  105/105 군국, 1,180/1,180 현급 단위, identity unique 1,180.
- 유형: COUNTY 1,043 / DAO 19 / MARQUISATE 108 / TOWN 10.
- 선언/열거 불일치: `安平國 13/12` 한 건만 존재.
- canonical group의 `巴陵秦置`, `龜茲屬國`: 각각 0건.
- `上郡` ordinal 9: source literal `龟兹属国`, correction `龜茲`, COUNTY.
- corpus/ctext snapshot hash 및 1,180개 unit citation line 전수 검사: 실패 0.
- 좌표 필드 0건, MediaWiki markup 누출 0건.
- artifact SHA-256:
  `668165bce575a618be5f30738221fe657b30710d0c92f7e984a018711313b19f`.
- whole dirty worktree `scripts/agent/verify-changes.sh --run`: exit 0. Gradle
  `BUILD SUCCESSFUL in 1m 42s`, XML 551 files / 4,737 tests / failures 0 / errors 0 /
  skipped 236. gateway/game typecheck green, game Vitest 76 files / 431 tests green,
  agent-system strict 102 changed / errors 0 / warnings 0 / findings 0.

## 잔여 경계

- corpus와 ctext snapshot은 gitignored다. 새 환경은 fetch 후 artifact에 기록된 exact hash를
  복원해야 하며, 다르면 생성기가 fail-closed한다.
- `SOURCE_PLACEHOLDER` 세 건은 W0-B/W0-C consumer가 정식 지명으로 승격하면 안 된다.
- W0-A에는 좌표·CHGIS join·playable 780 선택·save migration이 없다.
