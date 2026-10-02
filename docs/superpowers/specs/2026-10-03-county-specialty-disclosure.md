# County specialty disclosure

The user decision of 2026-10-03 separates the public design values of a county's
resource sites from its current monthly allocation.

`GET /api/county/{cityId}?generalId={id}` keeps the existing nullable
`specialties[].monthly` and non-null `specialties[].ledgerMonthly` contract.
`ledgerMonthly` remains available for another nation's county, an unowned county,
and a ronin viewer. `monthly` is disclosed only when the authenticated account
owns the selected general, that general belongs to a positive nation, and the
county belongs to the same nation in the same world.

FULL visibility, intelligence, presence at a county, and signed administrator
role do not grant access to another nation's actual monthly allocation. Hidden
allocation is null or omitted, including a hidden allocation whose value is zero.
The server does not calculate private monthly allocation for an unauthorized
county. Eligible own counties retain the existing `CountyIncome.monthly`
calculation, including zero for disconnected supply or a missing warehouse and
null for malformed warehouse state.

Authentication stays bound to the verified JWT account and the real selected
general row. Anonymous, invalid and expired tokens receive 401. Another account's
or nonexistent general receives 403 before target county or artifact reads.
Query parameters cannot replace the authenticated principal or grant ownership.
Existing process-world and artifact authority are retained.

Regression evidence must include the same authenticated viewer reading an own
county and another nation's county, ronin and unowned counties, and the actual
JWT filter/controller/reader chain. Resource hold forbids local JVM runs; execute
the permanent regression on normal CI, preserve actual red then green evidence,
and report any unverified result explicitly.
