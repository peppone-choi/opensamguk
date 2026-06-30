# 2026-06-30 turn-drain-miniche

Implementer claim: `TurnDaemonLifecycle` now pulls the nation/general rings immediately after each handled general, `DaemonLoopConfig` wires the real repository pull methods, and the engine tests cover both tail advancement and ring consumption.

Critique: The backend change preserved the existing killturn / updateTurnTime order and only restored the missing ring-drain side effect. The main remaining surface risk was `miniche_*` map assets in the lobby and game map preview; that was corrected by sending miniche variants to `che/bg_*.jpg` plus `che/miniche_road.png`.

Verdict: cleared.

Result: cleared.

Verification: `:app:game-engine:test` passed; `web/game` typecheck passed; `web/gateway` typecheck passed; `web/game` `MapViewer.interaction.test.tsx` passed after the miniche asset fix.
