# OFFICE source/context projection

This package prepares immutable native jurisdiction and appointment contexts from typed source facts.
It does not read a database, authenticate a principal, call a mutation executor, or issue an office.
`OfficeJurisdictionProducer.projectAppointmentSources` and `projectCapabilitySources` are pure.
There is no production source adapter or caller attached to this preparation component.

## Observation and source sections

`OfficeSourceBinding` carries the actual world, selected `WorldMapVariant`, topology revision/hash,
selected administrative artifact hash, and observed native `Phase`. It is an observation binding,
not a mutation revision, CAS token, lease, or source authentication receipt. Every known section must
match that binding exactly, including variant, physical hashes, and phase. A same-size map is not the
same variant or artifact.

`OfficeSourceSection.Known(value, binding, mutationRevision)` records an observed value. A known
empty list/set, neutral owner `nationId=0`, or known null holder location is distinct from
`Unavailable(MISSING/CORRUPT/UNVALIDATED)`. A missing mutation revision remains null and is reported
in `unversionedSections`; only actually supplied revisions appear in `mutationRevisions`. No default
zero, timestamp, codec version, topology pin, or content hash supplies a missing mutation revision.
A known unversioned value does not prove durable CAS or protection against ABA.

The source adapter owns validation of physical source origin and capture consistency before declaring
`Known`. These Kotlin types do not authenticate untrusted wire values. They have no raw JSON codec.
Do not reconstruct expected client terms from current state or treat source presence as authorization.

## Minimal appointment facts and complete native structure

A source adapter supplies the selected world's administrative county inventory and a **complete target
jurisdiction** canonical join validated against that selected variant. Each joined county must be in
that inventory, have the requested canonical jurisdiction, and have a unique live tiles jurisdiction.
Runtime `meta.junCh` commandery text is not the fixed HHS canonical commandery/province axis. Fixed-axis
bytes must be reconciled with the selected artifact before the join is declared known.

Live owner rows must uniquely and completely match those target counties and live jurisdictions.
Scenario/static fallback, commandery majority/control, and missing live city rows are not current county
ownership. Neutral live ownership is a known zero; missing ownership is unavailable.

The current seat must be an actual current-world source in the target county set. A base map seat,
tiles seat, requested seat, or a personal tenure seat cannot replace a missing current-seat overlay.
Current-seat value/revision and API ownership mutation revision remain unavailable/unversioned until
actual producers supply them. The producer never calls a seed loader or guesses these facts.

Native appointment policy inspects actor/nation/living/retired facts, current county scope/seat/ownership,
actual tenure records, and existing native catalog/rules/central-office identities. Source structural
validation does not grant authority or reproduce `OfficeAppointmentRules` policy. Non-ruler, retired,
foreign-nation, and changed-seat facts remain known contexts; native rules perform the denial.
A missing tenure store is not a verified empty store. Ended records are retained and duplicate IDs are
rejected rather than silently normalized.

Holder location, fresh warehouse connectivity, actually seated magistrates, and actually stationed
corps are capability evidence. They are not newly added appointment qualifications. However, the
existing `OfficeAppointmentContext` structurally requires a complete `OfficeJurisdictionSnapshot`.
Until every section is actually supplied, projection returns `Unavailable`, retaining known-section
and revision diagnostics. It never fabricates null/empty values for missing sections to build that
native object. When complete, it copies and freezes collection values. `Ready` means only that the
native context is structurally available: it does not approve cost, delivery, a grant, or effective
control. Consumers must still call native rules on fresh facts in the authorized execution unit.

## Remaining production source gap

Actual appointment completion still needs the current-world seat overlay and provenance, a validated
selected-variant canonical join, and authoritative tenure ID/appointed-and-accepted turn binding.
C3 fresh supply, seated magistrate, and stationed-corps facts are not supplied by this component.
No `OfficeJurisdictionSourceReader` or engine source capture is implemented here. Do not borrow a
preserved supply flag, pending placement, or merely deployed corps as those actual facts.

## Future writer boundary

Missing/unavailable facts must return before any recorder/world mutation. Approved cost and actual
intake authority remain separate gates. The currently prepared response route stays denied while
cost is unavailable; this component changes no catalog, delivery stage, GET option, or response handler.

A separately owned completion writer must encode/validate the tenure result before mutation, then
record the office KV delta before replacing the live world value. All linked offer/tenure mutations
must share the paired world/recorder savepoint of the same `TurnUnitExecutor` unit. Immediate command
dispatch does not automatically inherit the general-turn savepoint. Exceptions after recording must
escape that unit so paired rollback happens before a denial/result is translated. Restore failures,
infrastructure failures, and interruptions retain the existing recovery-gate behavior. External observer
side effects and durable flush/replay need their own source contract and evidence; recorder-first order
alone does not establish atomic persistence or monotonic revisions.

This component does not edit `CourtStateStore`, the OFFICE handler hunk, shared unit/recorder, C3/C4
source writers, or API readers. Successful appointment, DB flush/cold reload, durable retry, and
production activation remain unimplemented here.

## Focused verification

The new selector is `:logic:test --tests opensamguk.logic.office.OfficeJurisdictionProducerTest`.
It covers source absence versus known null/empty, complete native structure, binding mismatch,
canonical/live joins, current seat, revision preservation, immutable copies, and native policy delegation.
It is independent of the existing OFFICE response codec/handler and response-writer selectors.
