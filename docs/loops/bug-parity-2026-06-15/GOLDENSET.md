# GOLDENSET — bug-parity-2026-06-15 루프 (패러티 모드)

> 상태: **초안 — 승인 대기**. 이 문서는 신규 골든셋이 아니라 *이미 동결된 게이트 시험지를
> 가리키는 포인터*다 (page-parity 루프 GOLDENSET와 동일 시험지를 공유). 새 채점 기준을
> 추가하지 않으며 무엇도 완화하지 않는다 (CLAUDE.md 패러티 규율 5 = fix the impl,
> not the golden). 유저 승인 시 동결.

## 시험지 (고정 — 기존 게이트 재사용)

1. **백엔드 게이트**: `tools/parity/gate.sh backend`
   - 채점 = 각 모듈 `build/test-results/test/*.xml`의 `failures="0" errors="0"` 전수
     + 스위트 수 비감소. exit code 비신뢰(CLAUDE.md). `--rerun-tasks`로 UP-TO-DATE 위장 방지.
   - Docker 미가동 시 Testcontainers IT는 **skip**(=fail 아님). infra/engine/api의 IT는
     Docker 있을 때만 채점에 포함; 없으면 common+logic 결정적 게이트가 코어 시험지.
2. **갭/버그 단위 골든**: 한 바퀴가 특정 명령/경로의 패러티 버그를 고칠 때,
   해당 `*GoldenTest`/`*GateTest`의 green 유지(회귀 0)가 채점이고,
   새 커버리지가 필요하면 PHP 소스 인용 골든(`golden-capturer`, 날조 금지)을 추가해 red→green을 관찰한다.

## 채점 규칙

- 채점자 = 제안 컨텍스트 없는 **fresh 서브에이전트**(parity-gate-runner 또는 XML을 직접
  읽는 fresh agent). 결정적(test XML) 채점 → 블라인드 A/B 불요.
- 점수 상승 정의: (a) 시험지 전체 green 유지(회귀 0) + (b) 대상 버그 닫힘
  (가능 시 신규/기존 테스트 red→green + PHP 오라클 대조 통과).
- 동점 = green 유지지만 버그 미닫힘. 하락 = 시험지 red.
- 1바퀴 = 1버그(가설 1개). 갭 N개 일괄 확정 금지 (= 골든 완화의 거울상).

## 변경 금지

골든/게이트/이 문서/CLAUDE.md 패러티 규율의 변경 = 점수 무관 **유저 승인 필수**.
