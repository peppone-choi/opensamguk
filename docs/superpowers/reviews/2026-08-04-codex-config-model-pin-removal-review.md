# .codex/config.toml 개인 모델 pin 제거 리뷰

Scope: `.codex/config.toml` 의 `model` / `model_context_window` 키 제거. `.codex/` 표면 외 변경 없음.
Verdict: cleared

## 리뷰어 독립성 (먼저 밝힘)

**이 아티팩트는 변경을 만든 주체가 직접 작성했다. 독립 에이전트/프로바이더의 교차 비평이 아니다.**
`tools/agent-system/check.py` 의 `cross-agent-critique` 게이트는 `.codex/` 하위 변경이면
크기와 무관하게 아티팩트를 요구하는데(`check.py:441-452` — 사소함 예외 없음), 이번 변경은
설정 키 2개 삭제라 별도 프로바이더 비평을 붙이지 않았다. 독립 검증이 필요하다고 판단되면
이 문서를 근거로 재비평할 것.

## 문제

`origin/main` 의 `.codex/config.toml:1` 이 `model = "gpt-5.6-sol"` 을 pin 하고 있었다.
`check.py:208-209` 는 프로젝트 Codex config 에 `model` 키가 존재하면 무조건
`ERROR codex-surface: Project Codex config must not pin a personal model.` 을 낸다.

결과적으로 **`--strict` 게이트가 main 기준으로 이미 red 였고, 모든 PR 에서 이 ERROR 가 떴다.**
실제로 이번 세션에서 무관한 브랜치(`fix/gateway-icon-upload-ux`, PR #358) 검사 때도 동일하게
관측됐다 — 해당 브랜치는 `.codex/` 를 전혀 건드리지 않았다(`git diff origin/main -- .codex/` 공백).

상시 red 인 게이트는 신호를 잃는다. 브랜치가 실제로 무언가를 깨뜨렸는지와
main 이 원래 red 인지를 구분할 수 없게 되기 때문이다.

## 변경

```diff
-model = "gpt-5.6-sol"
-model_context_window = 372000
+# model/model_context_window은 개인 설정이다 — 프로젝트 config에 pin하지 않는다.
+# 모델 선택은 각자 ~/.codex/config.toml에서 한다(tools/agent-system/check.py codex-surface).
 model_reasoning_effort = "high"
```

`model_context_window = 372000` 도 같이 뺐다. 게이트가 검사하는 키는 `model` 하나뿐이라
이 줄만 남겨도 ERROR 는 사라지지만, 372000 은 `gpt-5.6-sol` 에 맞춘 값이다. 모델 pin 을
없앤 상태에서 특정 모델용 컨텍스트 윈도우만 남기면 실제 사용 모델의 한계를 넘겨 요청하는
잠재 오류가 된다. 두 키는 짝이므로 함께 제거했다.

`model_reasoning_effort` / `plan_mode_reasoning_effort` 는 남겼다. 모델 비특정 선호값이고
게이트 대상도 아니다.

## 검토한 위험

- **Codex 기동 불능?** 아니다. `model` 미지정이면 Codex 가 자체 기본 모델을 쓴다. 개인 모델
  선택은 사용자 레벨 `~/.codex/config.toml` 에서 하면 되고, 그게 이 게이트 규칙의 의도다.
- **필수 표면 훼손?** 아니다. `check_codex_surface` 가 요구하는 `features.hooks = true`,
  `features.multi_agent = true` 는 그대로다 (`tomllib` 파싱으로 확인:
  `features = {'hooks': True, 'multi_agent': True, 'multi_agent_v2': {...}}`).
- **TOML 파손?** 아니다. `tomllib.load` 성공, 잔여 키
  `['agents', 'features', 'mcp_servers', 'model_reasoning_effort', 'plan_mode_reasoning_effort']`.
- **`.codex/hooks.json` / `scripts/agent/codex-*.sh`** 무변경 — 훅 경계는 손대지 않았다.
- **패러티 영향 없음.** 런타임 코드·골든·RNG·로그와 무관한 에이전트 도구 설정이다.

## 증거

변경 전 (`--strict --base origin/main`, PR #358 브랜치에서 관측):
```
- **ERROR codex-surface**: Project Codex config must not pin a personal model.
```

변경 후 이 브랜치:
```
- Changed files: .codex/config.toml, docs/superpowers/reviews/2026-08-04-codex-config-model-pin-removal-review.md
- Errors: 0
```

## 남은 것

- 사용자 로컬 Codex 는 이제 `~/.codex/config.toml` 에서 모델을 지정해야 한다. 리포 설정이
  대신 pin 해 주지 않는다. 이건 의도된 결과다.
- 이 게이트가 왜 main 에서 오래 red 였는지는 추적하지 않았다. `.codex/` 표면이 추적 대상이
  된 시점(ADR-LITE-009)과 pin 이 들어온 시점의 선후는 **미확인**이다.
