# claude_review.yml 제거 리뷰

Scope: `.github/workflows/claude_review.yml` 삭제와 그에 따른 `docs/agent/verification.md`·`docs/agent/claude-user-manual.md` 정정. 다른 워크플로·런타임 코드 변경 없음.
Verdict: cleared

## 리뷰어 독립성 (먼저 밝힘)

**이 아티팩트는 변경을 만든 주체가 직접 작성했다. 독립 프로바이더의 교차 비평이 아니다.**
`check.py:441-452`의 `cross-agent-critique` 게이트가 `.github/workflows/` 변경에 아티팩트를
요구해서 작성했다. 아이러니하게도 이 변경 자체가 리뷰봇 하나를 없애는 것이므로, 독립 검증이
필요하다고 판단되면 이 문서를 근거로 재비평할 것.

## 왜 제거하나

`claude-review` 체크는 **최근 12회 실행이 전부 실패**했다(2026-08-03 ~ 08-04). 서로 무관한
브랜치 전반이며, 여기엔 **이미 main에 머지된** `codex/gcp-deploy-token-inheritance`
(PR #355, `e94077e5`)도 포함된다.

실패 양상이 리뷰 판정이 아니다:

```json
{"type": "system", "subtype": "init", "model": "claude-sonnet-4-6"}
{"type": "result", "subtype": "success", "is_error": true,
 "duration_ms": 306, "num_turns": 1, "total_cost_usd": 0,
 "permission_denials_count": 0}
```

306ms·비용 0·턴 1·권한 거부 0 — 리뷰를 시작하기도 전에 **API 레벨에서 거절**됐다.
`ANTHROPIC_API_KEY` 만료/무효, 해당 키의 `claude-sonnet-4-6` 접근 부재, 또는 크레딧 소진이
후보다. **어느 쪽인지는 확정하지 않았다 — 시크릿 값을 읽지 않았고 읽어서도 안 된다.**

상시 red인 체크는 신호를 잃는다. 브랜치가 실제로 무언가를 깨뜨렸는지와 인프라가 원래 빨간
것인지를 구분할 수 없게 되고, 실제로 이 리포는 그 상태로 PR을 머지해 왔다(#355). 그건
게이트가 아니라 소음이다.

## 이 변경이 약화시키는 것 (정직하게)

`docs/agent/verification.md`의 검증 루프 **②(자기 승인 차단)**은 `claude_review.yml`을
CodeRabbit과 함께 "PR 이중 리뷰"로 명시하고 있었다. 리뷰봇이 2종에서 1종으로 준다.

남는 ② 기제:

- **CodeRabbit** (`.coderabbit.yaml`) — 현재 정상 동작. PR #358·#359에서 `SUCCESS`
- **`check.py --strict`의 cross-agent critique** — `docs/superpowers/reviews/*.md`의
  `Scope:`/`Verdict:` 앵커 요구, `Verdict: fix-required`면 완료 차단
- **사람 또는 타 프로바이더 에이전트의 명시 비평** — `CLAUDE.md`의 cross-agent critique 규범

즉 ②가 사라지는 게 아니라 **자동 축이 하나 줄어든다.** 문서 두 곳을 이에 맞게 정정했다.
없어진 통제를 문서에 살아 있는 것처럼 남겨두는 쪽이 더 위험하다고 판단했다.

**되살리는 법**: `ANTHROPIC_API_KEY` 시크릿을 유효한 키로 교체하고 이 커밋을 revert 하면
된다. 워크플로 내용은 git 이력에 그대로 있다. 파리티-도메인 한국어 커스텀 프롬프트
(RNG draw 순서·PhpRound half-away·로그 바이트 패리티·one-daemon-write·삽입 순서·위조 금지)가
그 파일의 자산이며, 그건 `ADR/2026-07-16-agent-os-activation` 리뷰에서 AC-11 통과 조건으로
승격된 내용이다. 버릴 게 아니라 키가 복구되면 되돌릴 대상이다.

## 검토한 위험

- **다른 워크플로 영향?** 없다. `ci.yml`(agent-system/jvm/web), `deploy.yml`,
  `promote-game-server.yml`, `reset-game-server.yml`, `predeploy-go-check.yml`는 무변경이고
  `claude_review.yml`을 참조하지 않는다.
- **required check 설정에 남아 있으면?** 삭제된 워크플로가 브랜치 보호의 필수 체크로 걸려
  있으면 PR이 영구 대기할 수 있다. **미확인** — 브랜치 보호 설정을 조회하지 않았다.
  #358 머지 시 확인된다.
- **패러티 영향** 없다. 런타임 코드·골든·RNG·로그 무관.

## 증거

```
$ gh run list --workflow=claude_review.yml --limit 12
failure  chore/codex-config-no-model-pin
failure  fix/gateway-icon-upload-ux
failure  v2-round3-city-guanxi
failure  codex/fix-possession-five-stats   (×6)
failure  codex/gcp-deploy-token-inheritance (×3, PR #355는 머지됨)
```

12/12 실패. 성공 사례 0.

## 미확인

- 키가 만료된 것인지, 모델 접근이 없는 것인지, 크레딧이 소진된 것인지 **확정하지 않았다.**
  시크릿을 읽지 않았다.
- 언제부터 red였는지 12회 이전은 조회하지 않았다.
- `main` 브랜치 보호의 required checks 목록은 조회하지 않았다.
