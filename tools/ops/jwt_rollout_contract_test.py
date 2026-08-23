#!/usr/bin/env python3
from pathlib import Path
import re


root = Path(__file__).resolve().parents[2]
workflow = (root / ".github/workflows/deploy.yml").read_text(encoding="utf-8")

board_restart = workflow.index('echo "=== Restarting board API ==="')
capability_gate = workflow.index('"verifier":"rsa-audience-v1"', board_restart)
gateway_restart = workflow.index('echo "=== Restarting gateway migrations ==="', capability_gate)
assert board_restart < capability_gate < gateway_restart
assert 'jwt_signing_mode="${jwt_signing_mode:-RS256}"' in workflow
# OPENSAM-220/#483: display claims are unconditionally never issued now (no
# flag to gate them off), so the cutover gate no longer branches on
# include_profile_claims — it only needs to know the signing mode.
assert 'include_profile_claims' not in workflow
assert '[[ "$jwt_signing_mode" == "RS256" ]]' in workflow
assert 'api_container="${internal_server}-game-api"' in workflow
assert 'must be running and promoted before gateway cutover' in workflow
assert 'expected_jwt_public_key_sha256=' in workflow
assert "publicKeySha256" in workflow
assert 'is not using the configured JWT public key' in workflow

for compose_name in ("docker-compose.yml", "docker-compose.production.yml"):
    compose = (root / compose_name).read_text(encoding="utf-8")
    def service(name: str) -> str:
        match = re.search(rf"(?ms)^  {name}:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)", compose)
        assert match is not None
        return match.group("body")

    gateway = service("gateway-api")
    board = service("board-api")
    game = service("game-api")
    web_gateway = service("web-gateway")
    web_game = service("web-game")
    assert "JWT_PRIVATE_KEY" in gateway
    assert "JWT_PUBLIC_KEY" in gateway
    assert "JWT_PUBLIC_KEY" in board and "JWT_PRIVATE_KEY" not in board
    assert "JWT_PUBLIC_KEY" in game and "JWT_PRIVATE_KEY" not in game
    for web in (web_gateway, web_game):
        assert "JWT_PRIVATE_KEY" not in web
        assert "JWT_LEGACY_SECRET" not in web
        assert "JWT_PUBLIC_KEY" not in web

web_admin = (root / "web/gateway/app/admin/page.tsx").read_text(encoding="utf-8")
assert "jwtPublicKey" in web_admin
assert "jwtPrivateKey" not in web_admin
assert "jwtLegacySecret" not in web_admin
assert "JWT_PRIVATE_KEY" not in web_admin
assert "JWT_LEGACY_SECRET" not in web_admin

for verifier in (
    root / "app/game-api/src/main/kotlin/opensamguk/gameapi/security/GameApiJwtVerifier.kt",
    root / "app/board-api/src/main/kotlin/opensamguk/boardapi/security/BoardApiJwtVerifier.kt",
):
    source = verifier.read_text(encoding="utf-8")
    assert '"publicKeySha256"' in source

print("PASS: JWT consumer-first rollout, key identity, and asymmetric boundary are gated")
