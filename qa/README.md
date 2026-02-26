# QA Parity Tests — OpenSam (삼국지)

Side-by-side comparison of the **legacy PHP/MariaDB** stack and the **new Kotlin/PostgreSQL** stack to verify logical equivalence.

## Quick Start

```bash
# From the project root:
docker compose -f qa/docker-compose.parity.yml up --build
```

This starts:

| Service          | Port | Description                |
| ---------------- | ---- | -------------------------- |
| `legacy-app`     | 8180 | Legacy PHP + Apache        |
| `legacy-mariadb` | 3307 | MariaDB 10.11              |
| `new-gateway`    | 8080 | New Kotlin/Spring Boot     |
| `new-nginx`      | 8081 | New frontend (nginx proxy) |
| `new-postgres`   | 5433 | PostgreSQL 16              |
| `parity-runner`  | —    | Pytest test runner         |

## What Gets Tested

| Test Suite | File                         | What it checks                                        |
| ---------- | ---------------------------- | ----------------------------------------------------- |
| Auth       | `test_01_auth.py`            | Register, login, duplicate rejection, bad password    |
| Game Init  | `test_02_game_init.py`       | Scenarios, nations, generals, cities, diplomacy       |
| Commands   | `test_03_commands.py`        | Command reservation (징병, 내정, etc.), command table |
| NPC AI     | `test_04_npc_ai.py`          | NPC policy, NPC command categories                    |
| Battle     | `test_05_battle.py`          | Battle simulation structure, rounds, invalid input    |
| Turns      | `test_06_turn_processing.py` | Turn state, DB schema parity, history, map            |

## How Comparison Works

The test runner uses **structural comparison** rather than exact value matching because:

1. **Different RNG seeds** — both systems use deterministic RNG but produce different sequences
2. **Different field names** — legacy uses Korean (e.g. `통솔`), new uses English (`leadership`)
3. **Different DB engines** — MariaDB vs PostgreSQL type coercion differences
4. **Different auth mechanisms** — legacy uses PHP sessions, new uses JWT

The `comparison.py` module handles:

- Korean ↔ English field name mapping
- PHP string→number type coercion
- Structural shape comparison (compares types, not values)
- RNG-dependent field exclusion

## Results

After tests complete, find:

- **Console output**: pass/fail for each test
- **JSON report**: `qa/results/report.json`

## Running Individual Tests

```bash
# Interactive mode — start services, then run tests manually
docker compose -f qa/docker-compose.parity.yml up -d legacy-app new-gateway

# Enter the runner container
docker compose -f qa/docker-compose.parity.yml run --rm parity-runner bash

# Run specific test file
pytest tests/test_01_auth.py -v

# Run with keyword filter
pytest -k "battle" -v
```

## Debugging

```bash
# Check legacy app
curl http://localhost:8180/api.php?path=Global/GetConst

# Check new app
curl http://localhost:8080/api/scenarios

# Connect to legacy DB
mysql -h localhost -P 3307 -u root -prootpw sammo

# Connect to new DB
psql -h localhost -p 5433 -U opensam opensam
```

## Adding New Tests

1. Create `qa/parity-test/tests/test_NN_name.py`
2. Use `legacy` and `new` fixtures for API calls
3. Use `legacy_db` and `new_db` for direct DB access
4. Use `compare_responses()` from `comparison.py` for structured comparison
5. Use `structural_only=True` when exact values differ due to RNG

## Architecture

```
qa/
├── docker-compose.parity.yml    # Both stacks + test runner
├── setup-legacy-docker/
│   ├── Dockerfile               # Legacy PHP app image
│   └── entrypoint.sh            # DB setup + config generation
├── parity-test/
│   ├── Dockerfile               # Test runner image
│   ├── requirements.txt
│   ├── conftest.py              # Shared fixtures (clients, DB connections)
│   ├── comparison.py            # Comparison engine (field mapping, structural diff)
│   └── tests/
│       ├── test_01_auth.py
│       ├── test_02_game_init.py
│       ├── test_03_commands.py
│       ├── test_04_npc_ai.py
│       ├── test_05_battle.py
│       └── test_06_turn_processing.py
└── results/                     # Test output (gitignored)
    └── report.json
```
