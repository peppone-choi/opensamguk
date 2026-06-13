# GOLDENSET — 5-stat divergence + 패러티 재정렬 (2026-06-13)

**모드: 패러티(parity).** 기존 골든 게이트가 곧 시험지. 새로 만들지 않고, 절대 완화하지 않는다.
유저 승인 불요(이미 frozen 게이트). 단 divergence 신규 행동에는 별도 채점 규약(아래)을 둔다.

## 동결 시험지 = 기존 패러티 게이트

표준 채점 명령(결정적, fresh 서브에이전트가 실행·판독):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) tools/parity/gate.sh backend
```

판정 기준 (gate.sh 내장):
- `BUILD SUCCESSFUL` 존재
- 전 모듈 TEST XML: `failures=0 errors=0`
- 통과 시 `XML gate green: N suites, T tests`

이 게이트가 곧 **3스탯(통솔/무력/지력) draw-for-draw 패러티 불변식**의 시험지다.
포함: common(RNG/log/round 커널), logic(ActionPipeline·battle·ai·monthly 골든), infra(flush IT), engine, api.

## 합격선

| 항목 | 합격 조건 |
|---|---|
| P1 패러티 무회귀 | 게이트 green. 베이스라인 대비 failures/errors 증가 0. |
| P2 스탯 커널 격리 | leadership/strength/intel 의 `getStatValue` 경로·RNG draw·로그 문자열 **byte-unchanged**. |
| P3 골든 무완화 | `*GoldenTest`/`*ReplayGateTest` 파일·골든 리소스 수정 0. |

## Divergence 채점 규약 (정치·매력 — PHP 오라클 없음)

정치·매력은 레거시에 없으므로 PHP 골든으로 채점 불가(패러티 규율 5번: fabricate 금지).
대신 다음으로 채점:
- D1 **무회귀가 1순위**: 정치·매력 추가가 위 P1–P3을 깨면 즉시 폐기·원복.
- D2 신규 필드/UI는 fresh 서브에이전트가 스펙(이 문서) 대비 존재·일관성만 채점. 숫자 오라클 없음 명시.
- D3 정치·매력은 **divergence로 라벨**(1.0.0+ 독자기능). 패러티 골든에 절대 섞지 않는다.

## 변경 금지 (frozen)

- 이 GOLDENSET.md (규칙 변경 = 유저 승인 필수)
- `tools/parity/gate.sh`, `*GoldenTest`, `golden/**` 리소스
- 채점 명령·집계 방식
