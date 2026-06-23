# GOLDENSET — loop-parity-2026-06-23

> 동결된 시험지. 변경하려면 사용자 승인 필요.  
> opensamguk 패러티/버그 루프에서는 기존 repo gate가 골든셋이다.

## 결정적 게이트

1. **Backend parity gate**
   - 명령: `tools/parity/gate.sh backend`
   - 합격 기준: `BUILD SUCCESSFUL` + 모든 모듈 `failures="0" errors="0"` XML.
2. **Frontend typecheck**
   - `cd web/game && pnpm typecheck`
   - `cd web/gateway && pnpm typecheck`
3. **Frontend unit test**
   - `cd web/game && pnpm test`
4. **Agent-system check**
   - `tools/agent-system/check.py --strict --base origin/main`

## 비결정적 루브릭

- 없음. 모든 채점은 위 결정적 게이트로만 평가한다.
