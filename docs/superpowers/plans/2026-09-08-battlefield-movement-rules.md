# Battlefield movement contract

Implement a pure catalog and entry/exit decision without modifying topology or city identity. The caller supplies reviewed same-province ingress bindings, current city anchors, authoritative spatial state and exact catalog hash. Changban's reviewed binding is runtime city 405 / province 45776; this is reconstructed gameplay deployment, not an attested ancient road.

1. Write focused regressions for entry/exit, stale revisions/catalogs, wrong nodes, withheld sites, invalid return and city-local action gating.
2. Add validated immutable catalog and pure assessment-producing rules. Every permitted transition consumes a movement turn in the caller.
3. Run focused tests in the coordinated Gradle slot. Root integrates persistence, commands and all city consumers separately.

No routes, nation-ownership rules, map edits or data loading are introduced here.
