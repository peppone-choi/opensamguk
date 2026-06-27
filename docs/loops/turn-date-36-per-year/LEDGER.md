# LEDGER — turn-date-36-per-year

| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 | 승인대기 |
|---|---|---|---|---|---|---|
| 0 | 베이스라인 | samnet 공개 화면 `218년 12월 상순`, myosam help 37페이지 수집, 기존 Kotlin 월간 하네스는 1턴=1개월 전제 | samnet root 관측, `tools/wiki/scrape_myosam_help.py`, 코드/테스트 조사 | 채택 | 외부 표면과 사용자 요구 모두 삼모 순 개념을 전제로 하는데 하네스와 world state는 phase를 내구 값으로 들고 있지 않았다 | 없음 |
| 1 | `current_phase`를 world state/read/reserved/realtime/flush에 관통시키고 replay gate를 3순=1개월로 고정 | `MonthTickReplayGateTest` 기존 월 단위 전제 실패 -> backend gate 437 suites / 3219 tests green | `tools/parity/gate.sh backend`, 대상 Gradle 테스트, `git diff --check` | 채택 | 달력 자체가 월이 아니라 상/중/하순 턴으로 진행되므로 하네스가 3번째 턴에서만 다음 달 상순을 기대해야 한다 | 없음 |

## Backlog

- v2 동적 전쟁 replay schema를 별도 spec으로 확정한다.
- 가신/추종/어전회의/부세력/봉건제는 v1 phase fix와 분리해 schema spike로 진행한다.
