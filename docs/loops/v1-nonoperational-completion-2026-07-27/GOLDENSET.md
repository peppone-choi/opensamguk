# v1 비운영 미완성 폐쇄 골든셋

## 동결 범위

- 기준 PHP: `legacy/devsam-core` commit `4de7ebec17a722d516608dbb987467f1a451dada`
- 기준 opensamguk: commit `0cbcf44626074f7e481d58b6e42defab164b6ea7`와 보존된 기존 작업 트리
- 대상: 감사 보고서 §6.1~§6.8 및 §8 중 production/S6 cutover를 제외한 모든 항목
- 제외: production deploy/cutover, live EC2, v2, 외부 tracker, legacy/golden 쓰기

## 채점 항목

1. PHP 92개 고유 명령이 사용자 form, intake 또는 예약, daemon, JDBC flush, terminal result, UI까지 닫힌다.
2. 월 틱과 이벤트가 PHP 12개월 replay에서 날짜, 수치, 행 순서, 로그 및 이벤트 순서가 일치한다.
3. 전투와 점령이 RNG draw, 반올림, 양측 모듈, rank/nation/diplomacy delta, typed log/event/message에서 일치한다.
4. production AI가 4층 정책, vacation/autorun, 실제 6/12월 입력을 사용하며 12개월 replay가 일치한다.
5. betting/select-pool/vote/inheritance/tournament/auction/diplomacy/mailbox lifecycle이 terminal result와 PHP 부수효과까지 일치한다.
6. 모든 world-owned read가 process `WorldId`를 강제하고 동일 local ID의 두 월드 및 restart-rehydrate가 손실 없이 통과한다.
7. core-live PARTIAL/DEAD frontend route가 실제 API 상태와 daemon terminal result를 표시하며 인증된 브라우저 E2E가 통과한다.
8. stored log의 prefix/date/scope/category/order가 PHP capture와 byte 단위로 일치한다.
9. `tools/parity/gate.sh backend`, `web/gateway` 및 `web/game` gates, local Docker smoke가 모두 fresh green이다.
10. 독립 리뷰가 `cleared`이며 미해결 `fix-required`가 없다.

## 판정 규칙

- 각 바퀴는 가설 하나만 바꾸고 같은 targeted test/capture로 재측정한다.
- 점수 상승 또는 RED→GREEN이면 채택, 동점/하락이면 해당 바퀴 변경만 원복한다.
- 실행하지 못한 항목은 pass가 아니라 `채점대기`다.
- PHP capture 없이 새 수치·로그·RNG 기대값을 만들지 않는다.
- 기존 golden/test 약화, skip 전환, legacy 쓰기는 금지한다.

## 2026-07-29 종결 채점

동결 범위의 1–10은 아래 evidence로 재채점했다. `PASS`는 **비운영** 범위의
PASS이며 production/S6를 포함하지 않는다.

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| 1. 92 command cross-layer | PASS | PHP 93파일/92 unique matrix, ordered 복합 인자 13종, terminal/UI 경로 |
| 2. 월 틱·이벤트 | PASS | schema 4 PHP 12개월·36순 A/B capture + Kotlin authoritative replay |
| 3. 전투·점령 | PASS | PHP two-run sortie/점령 capture, daemon·flush·rank/nation/diplomacy 회귀 |
| 4. production AI | PASS | four-layer policy/실제 입력을 포함한 12개월 exact replay, 7,428 handled drains |
| 5. side systems | PASS | betting/select-pool/vote/inheritance/tournament/auction/diplomacy/mailbox terminal·부수효과 증거 |
| 6. world scope/restart | PASS | scoped facade, real Spring context, 36순 flush-retry/restart, local Docker persistence |
| 7. frontend core-live | PASS | 인증 Playwright의 join terminal→front state 및 14 DOM route |
| 8. stored logs | PASS | PHP stored-log capture 및 byte comparison을 regression evidence로 소비 |
| 9. broad gates/local Docker | PASS | backend 550/4,753 0 failure/error; frontend 46/227; runtime9 1 passed |
| 10. independent review | PASS | `.omo/evidence/v1-final-code-review.md`: CLEAR / APPROVE / blockers none |

### 최종 Agent OS 전체-worktree 증거

checker의 cleared/quarantined disjoint Scope union 수정 뒤
`scripts/agent/verify-changes.sh --run`을 정확히 한 번 재실행했다. Gradle 5개
모듈은 `BUILD SUCCESSFUL in 13m 27s` / 29 tasks, `web/game`은 typecheck + 46
files / 227 tests, Agent OS contract와 diff/whitespace는 PASS다. cross-agent
finding은 독립 scope-union review의 `cleared`로 제거됐다.

다만 strict checker는 error 1 / warning 0이고 exit 1의 유일한 원인은 이
작업에서 수정하지 않은 사용자 소유 `.codex/config.toml` 최상위 personal model
pin이다. 그러므로 이 골든셋의 비운영 PASS를 strict green이나 ship/merge ready로
읽지 않는다. 증거는
[verify-changes.log](../../../.omo/evidence/v1-final/verify-changes-final2/verify-changes.log)와
[exit-code.txt](../../../.omo/evidence/v1-final/verify-changes-final2/exit-code.txt)에 있다.
