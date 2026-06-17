# Loop Engineering

이 문서가 opensamguk의 Claude/Codex 공용 루프 엔지니어링 정본이다.
`.claude/skills/loop-engineering/SKILL.md`와 `.agents/skills/loop-engineering/SKILL.md`는 이 문서를 읽는 얇은 어댑터여야 한다.

목표는 한 가지다: **측정 -> 가설 1개만 변경 -> 같은 시험지로 재측정 -> 채택/원복**.
측정 없는 진화는 금지다. "좋아진 느낌", "diff가 작다", "되돌릴 수 있다"는 채점이 아니다.

## 적용 대상

- **AI 설정 자산**: `AGENTS.md`, `CLAUDE.md`, `.agents/skills/`, `.claude/skills/`, agent prompts, 라우팅 규칙.
  골든셋은 `docs/loops/<loop-name>/GOLDENSET.md`에 둔다.
- **패러티/버그 작업**: 기존 repo gate가 골든셋이다.
  예: `tools/parity/gate.sh backend`, `*GoldenTest`, `*ReplayGateTest`, `*GateTest`.
  골든/게이트를 완화하지 않는다.

## Provider 표면 매핑

| 목적 | Claude | Codex |
| --- | --- | --- |
| 계획/상태 | `/ce-*` 또는 Todo | `update_plan` |
| fresh 채점자 | `Task`/`Agent` fresh reviewer | Codex native subagent가 있으면 verifier/reviewer, 없으면 결정적 gate만 직접 채점 |
| 수동 편집 | `Edit`/`Write` | `apply_patch` |
| 검색 | `Grep`/`Glob` | `rg`, `rg --files` |
| 브라우저 QA | `/browse`, webapp-testing | Playwright, webapp-testing, 또는 사용 가능한 Codex 브라우저 표면 |
| 보고 | Claude 최종 보고 | 한국어 최종 보고 |

provider별 도구 이름은 달라도 루프 규율은 같다. 도구가 없으면 규칙을 완화하지 말고 `승인대기` 또는 `채점대기`로 기록한다.

## 시작 절차

1. 현재 작업에 필요한 운영 규칙을 확인한다: `docs/superpowers/WORKING_SYSTEM.md`, `AGENTS.md`, `CLAUDE.md`.
2. 루프 이름을 정하고 `docs/loops/<loop-name>/GOLDENSET.md`와 `LEDGER.md`를 찾는다.
3. 없으면 초안을 만든다. 골든셋 초안은 만들 수 있지만, 동결과 첫 변경은 사용자 승인 후다.
4. 승인된 골든셋이 있으면 변경 전에 0바퀴 베이스라인을 실행하고 `LEDGER.md`에 기록한다.
5. 실행 계획에는 현재 바퀴 산출물만 넣는다. 끝내지 않을 후속 작업은 계획에 넣지 않는다.

## 한 바퀴 규칙

바퀴마다 아래 6요소를 모두 명시한다. 하나라도 빠지면 바퀴 무효다.

1. **베이스라인 점수**: `LEDGER.md` 0바퀴 행 또는 직전 바퀴의 채택 점수.
2. **가설 1개**: 변경 단위는 파일/커밋이 아니라 원인 가설이다. 나머지는 LEDGER 백로그.
3. **채점자**: 결정적 gate는 명령/테스트 이름. LLM 루브릭은 fresh subagent 또는 블라인드 A/B.
4. **합치기/원복 기준**: 바퀴 시작 스냅샷 또는 clean diff 기준을 명시한다.
5. **LEDGER 행**: `| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정(채택/폐기/승인대기) | 원인 한 줄 |`
6. **승인 대기 항목**: 없으면 `없음`.

## 채택/폐기 규칙

- 점수 상승: 변경 유지, LEDGER에 `채택`.
- 동점/하락: 바퀴 시작 상태로 byte-identical 원복, LEDGER에 `폐기`.
- 결정적 테스트가 채점자인 경우에는 동일 명령 재실행 또는 diff-0 확인으로 원복 검증 가능.
- 루브릭/LLM 채점처럼 비결정적이면 전/후 블라인드 A/B가 필수다.
- 채점 미완 상태로 중단되면 바퀴 시작 상태로 원복한다.

## 승인 필요 항목

다음은 점수가 올라도 자동 적용하지 않는다. 제안만 하고 사용자 승인 대기로 둔다.

- `GOLDENSET.md` 변경
- gate 스크립트나 테스트 기대값 완화
- `AGENTS.md`/`CLAUDE.md`의 load-bearing 규칙 변경
- 이 문서 또는 loop-engineering skill 어댑터 변경

사용자 부재는 승인 불가 상태다. 임의 승인으로 해석하지 않는다.

## 패러티 모드

opensamguk 패러티/버그 루프에서는 PHP grand truth와 기존 gate가 우선이다.

레거시 갭, UI 패러티, 실서버 버그가 포함된 바퀴는 아래 스킬 체인을 생략할 수 없다.

1. **레거시 증거**: `opensamguk-php-oracle`로 `legacy/devsam-core` PHP source path + line range를 먼저 찍는다. UI 흐름은 PHP가 침묵할 때만 `hwe/ts/` Vue 경로 + line range를 함께 기록한다.
2. **UI 재현**: 화면 문제가 있으면 `webapp-testing`으로 Playwright/브라우저/API 관측을 남긴다. 로컬 서버가 필요하면 helper script는 `--help`를 먼저 실행한다.
3. **버그 수렴**: 예상 밖 동작이나 실패는 `systematic-debugging` 순서로 재현, 최근 변경, 데이터 흐름, working example 차이를 확인한 뒤 가설 1개만 세운다. 원인 확인 전 수정 금지.
4. **전체 루프**: 위 증거를 `loop-engineering` 바퀴의 베이스라인, 가설, 채점자, 채택/원복 기준에 묶는다.
5. **구현/채점**: Kotlin/Next 구현을 고친다. golden/test를 약화하지 않는다. 좁은 gate를 먼저 돌리고, 필요 시 `tools/parity/gate.sh backend`로 확장한다.
6. **실서버 확인**: UI/실서버 문제가 포함되면 브라우저/API/DB 중 실제 관측 가능한 표면으로 재확인한다.

위 체인 중 하나라도 사용할 수 없으면 이유를 LEDGER나 리뷰 아티팩트에 `채점대기`/`blocked`로 기록한다. 조용히 건너뛰고 ship/merge하지 않는다.

## AI 설정 자산 모드

에이전트 행동을 바꾸는 작업에서는 골든셋 Y/N 문항이 시험지다.

- 문항은 5~12개로 유지한다.
- 채점 프롬프트와 집계 방식도 승인/동결 대상이다.
- 채점자는 제안 컨텍스트를 몰라야 한다.
- subagent가 없으면 자기 채점으로 채택하지 않는다. `승인대기` 또는 `채점대기`로 기록한다.

## 빼기 주기

3바퀴마다 1회는 추가가 아니라 삭제 가설을 제안한다.
낡은 문구/중복 규칙/작동하지 않는 라우팅을 제거하고, 점수 유지 시 채택한다.
미루면 LEDGER에 이유를 기록한다.

## 레드 플래그

- 베이스라인 없이 편집 시작
- 한 바퀴에 가설 2개 이상
- 채점자 없이 본인이 성공 판정
- 골든셋이 git-ignored 경로에 있음
- 실패 변경을 기록하지 않고 넘어감
- 점수 정체를 이유로 골든셋이나 gate를 완화하려 함
- "구현을 고치는 거라 정당하다"며 gate 없이 확정

하나라도 보이면 편집을 멈추고 측정 단계로 돌아간다.
