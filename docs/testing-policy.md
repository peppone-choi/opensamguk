# Testing Policy (Draft)

This document summarizes the testing strategy for the Sammo/HiDCHe repository.
The key is to separate "DB behavior emulation" from "state/logic/flow validation"
so each layer is tested with the right scope.

## Core Principles

- Prefer Repository/DB Port interfaces over ORM mocks; split implementations:
  - InMemory Repository (Fake)
  - Real DB Repository (e.g., Prisma)
- Validate domain constraints in the logic layer where possible; DB constraints
  act as a secondary safety net.
- Use deterministic seeds for all gameplay-impacting RNG.

## Test Layers

### 1) Constraint Tests

Goal: ensure constraints (unique/foreign key/check) behave consistently in
both InMemory state and the DB.

- Unit tests: validate domain constraints with InMemory repositories.
- Integration tests: verify the same violations fail in the real DB.
- Mock target: InMemory Repository (Fake).
- Use real DB only in integration tests.

### 2) Game Logic / Command Tests

Goal: verify state input -> state output and that flush behaves as expected.

- Unit tests: run logic against InMemory state and assert outputs.
- Integration tests: execute the same command and confirm DB persistence.
- Mock target: InMemory Repository (Fake).
- "Send to DB" behavior is validated via real DB tests.

### 3) Turn Flow Tests

Goal: verify the end-to-end scheduler/turn processing flow.

- Nature: integration/system tests.
- Stack: Real DB + (test) Redis.
- RNG uses fixed seeds for determinism.
- Keep a small set of smoke/regression scenarios due to cost.

### 4) Scenario Build Tests

Goal: ensure scenario parsing and DB application are correct.

- Unit tests: parse/validate scenario files (map size, ID collisions, ranges).
- Integration tests: load scenarios into a test DB and verify key tables.

## Result Composition Guidelines

- Unit tests: fast, wide coverage.
- Integration tests: focus on real DB/Redis behavior.
- Smoke tests: minimal coverage of turn flow/build/scenario loading.
- Test-specific Rule Relaxations:
  - To simplify mocking and complex state preparation, the use of `any` type is allowed in test files.
  - Type casting tricks like `as unknown as YourType` are also permitted in test code for convenience.
  - However, test code must still be covered by TypeScript type checking to ensure API consistency and prevent regressions.

## MockDB Conclusion

- "InMemory Repository (Fake)" is more practical than an ORM mock.
- DB-level behavior (constraints/transactions/locks) belongs to integration tests.
