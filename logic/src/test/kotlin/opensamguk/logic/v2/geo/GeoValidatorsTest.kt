package opensamguk.logic.v2.geo

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun d(year: Int) = LocalDate.of(year, 1, 1)

private fun vt(from: Int, to: Int? = null) = ValidTime(d(from), to?.let(::d))

private fun place(
    id: String,
    key: String = id,
    time: ValidTime = vt(140),
    distinct: List<String> = emptyList(),
    budgetClass: PlaceBudgetClass = PlaceBudgetClass.ADMINISTRATIVE_SETTLEMENT,
) = PhysicalPlace(
    id = id,
    names = listOf(TemporalName(id, time)),
    type = PlaceType.SETTLEMENT,
    developmentClass = DevelopmentClass.TOWN,
    placeIdentityKey = key,
    placeBudgetClass = budgetClass,
    locationResolution = LocationResolution.RESOLVED_POINT,
    coordinate = GeoPoint(1.0, 2.0, "p1"),
    distinctPlaceClaimIds = distinct,
    time = time,
)

private fun seat(
    id: String,
    unit: String,
    role: SeatRole,
    placeId: String = "place-1",
    time: ValidTime = vt(140),
) = SeatAssignment(id, unit, placeId, role, time)

class SeatAssignmentValidatorTest {

    @Test
    fun `co-location of commandery and county seat on one place passes`() {
        val violations = validateSeatAssignments(
            listOf(
                seat("s1", "commandery-1", SeatRole.COMMANDERY_SEAT),
                seat("s2", "county-1", SeatRole.COUNTY_SEAT),
            ),
        )
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `sequential seats for the same unit and role pass`() {
        val violations = validateSeatAssignments(
            listOf(
                seat("s1", "county-1", SeatRole.COUNTY_SEAT, "place-a", vt(140, 189)),
                seat("s2", "county-1", SeatRole.COUNTY_SEAT, "place-b", vt(189)),
            ),
        )
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `overlapping periods for the same unit and role fail`() {
        val violations = validateSeatAssignments(
            listOf(
                seat("s1", "county-1", SeatRole.COUNTY_SEAT, "place-a", vt(140, 200)),
                seat("s2", "county-1", SeatRole.COUNTY_SEAT, "place-b", vt(189, 220)),
            ),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("overlapping"), violations.single())
    }

    @Test
    fun `identical rows fail as duplicates`() {
        val violations = validateSeatAssignments(
            listOf(
                seat("s1", "county-1", SeatRole.COUNTY_SEAT, "place-a", vt(140, 200)),
                seat("s2", "county-1", SeatRole.COUNTY_SEAT, "place-a", vt(140, 200)),
            ),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("duplicate"), violations.single())
    }

    @Test
    fun `repeated assignment id fails`() {
        val violations = validateSeatAssignments(
            listOf(
                seat("s1", "county-1", SeatRole.COUNTY_SEAT),
                seat("s1", "county-2", SeatRole.COUNTY_SEAT),
            ),
        )
        assertTrue(violations.any { it.contains("appears 2 times") }, violations.toString())
    }

    @Test
    fun `open ended periods overlap`() {
        val violations = validateSeatAssignments(
            listOf(
                seat("s1", "county-1", SeatRole.COUNTY_SEAT, "place-a", vt(140)),
                seat("s2", "county-1", SeatRole.COUNTY_SEAT, "place-b", vt(300)),
            ),
        )
        assertEquals(1, violations.size)
    }

    @Test
    fun `badges derive only from active seat assignments`() {
        val assignments = listOf(
            seat("s1", "county-1", SeatRole.COUNTY_SEAT, "place-a", vt(140, 189)),
            seat("s2", "commandery-1", SeatRole.COMMANDERY_SEAT, "place-a", vt(140)),
            seat("s3", "county-2", SeatRole.COUNTY_SEAT, "place-b", vt(140)),
        )
        assertEquals(
            setOf(SeatRole.COUNTY_SEAT, SeatRole.COMMANDERY_SEAT),
            badgesFor("place-a", assignments, d(150)),
        )
        assertEquals(setOf(SeatRole.COMMANDERY_SEAT), badgesFor("place-a", assignments, d(200)))
        assertEquals(emptySet(), badgesFor("place-z", assignments, d(150)))
    }
}

class PlaceIdentityKeyValidatorTest {

    @Test
    fun `distinct identity keys pass`() {
        assertEquals(emptyList(), validatePlaceIdentityKeys(listOf(place("p1"), place("p2"))))
    }

    @Test
    fun `same identity key in disjoint periods passes`() {
        val violations = validatePlaceIdentityKeys(
            listOf(
                place("p1", key = "xu-county", time = vt(140, 189)),
                place("p2", key = "xu-county", time = vt(189)),
            ),
        )
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `same identity key in the same period without a distinct claim fails`() {
        val violations = validatePlaceIdentityKeys(
            listOf(place("p1", key = "xu-county"), place("p2", key = "xu-county")),
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("xu-county"), violations.single())
    }

    @Test
    fun `mutual distinctPlaceClaimIds allows the duplicate`() {
        val violations = validatePlaceIdentityKeys(
            listOf(
                place("p1", key = "xu-county", distinct = listOf("p2")),
                place("p2", key = "xu-county", distinct = listOf("p1")),
            ),
        )
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `one sided distinct claim still fails`() {
        val violations = validatePlaceIdentityKeys(
            listOf(
                place("p1", key = "xu-county", distinct = listOf("p2")),
                place("p2", key = "xu-county"),
            ),
        )
        assertEquals(1, violations.size)
    }

    @Test
    fun `repeated place id fails`() {
        val violations = validatePlaceIdentityKeys(listOf(place("p1", key = "a"), place("p1", key = "b")))
        assertTrue(violations.any { it.contains("appears 2 times") }, violations.toString())
    }
}

class PlaceBudgetValidatorTest {

    private fun catalog(counts: Map<PlaceBudgetClass, Int>): List<PhysicalPlace> =
        counts.flatMap { (cls, n) -> (1..n).map { place("$cls-$it", key = "$cls-$it", budgetClass = cls) } }

    @Test
    fun `exact budget passes`() {
        val places = catalog(PlaceBudgetClass.entries.associateWith { it.budget })
        assertEquals(2_000, PlaceBudgetClass.TOTAL_BUDGET)
        assertEquals(emptyList(), validatePlaceBudget(places))
    }

    @Test
    fun `class count mismatch and total mismatch both reported`() {
        val counts = PlaceBudgetClass.entries.associateWith { it.budget }.toMutableMap()
        counts[PlaceBudgetClass.EXTERNAL_PLACE] = 499
        val violations = validatePlaceBudget(catalog(counts))
        assertEquals(2, violations.size)
        assertTrue(violations.any { it.contains("EXTERNAL_PLACE") }, violations.toString())
        assertTrue(violations.any { it.contains("1999") }, violations.toString())
    }

    @Test
    fun `missing class is reported`() {
        val counts = PlaceBudgetClass.entries.associateWith { it.budget }.toMutableMap()
        counts.remove(PlaceBudgetClass.MARITIME_REMOTE_GATE)
        val violations = validatePlaceBudget(catalog(counts))
        assertTrue(violations.any { it.contains("MARITIME_REMOTE_GATE count 0") }, violations.toString())
    }
}

class GeoContractInvariantTest {

    @Test
    fun `resolved point requires a coordinate`() {
        assertFailsWith<IllegalArgumentException> {
            PhysicalPlace(
                id = "p1",
                names = listOf(TemporalName("p1", vt(140))),
                type = PlaceType.SETTLEMENT,
                developmentClass = DevelopmentClass.TOWN,
                placeIdentityKey = "p1",
                placeBudgetClass = PlaceBudgetClass.ADMINISTRATIVE_SETTLEMENT,
                locationResolution = LocationResolution.RESOLVED_POINT,
                coordinate = null,
                time = vt(140),
            )
        }
    }

    @Test
    fun `candidate region must not fabricate an exact coordinate`() {
        val region = CandidateRegion(
            id = "r1",
            derivedFromUnitId = "commandery-1",
            envelope = listOf(
                GeoPoint(0.0, 0.0, "p1"),
                GeoPoint(1.0, 0.0, "p1"),
                GeoPoint(0.0, 1.0, "p1"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            PhysicalPlace(
                id = "p1",
                names = listOf(TemporalName("p1", vt(140))),
                type = PlaceType.SETTLEMENT,
                developmentClass = DevelopmentClass.TOWN,
                placeIdentityKey = "p1",
                placeBudgetClass = PlaceBudgetClass.ADMINISTRATIVE_SETTLEMENT,
                locationResolution = LocationResolution.CANDIDATE_REGION,
                coordinate = GeoPoint(5.0, 5.0, "p1"),
                candidateRegions = listOf(region),
                time = vt(140),
            )
        }

        val uncertain = PhysicalPlace(
            id = "p2",
            names = listOf(TemporalName("p2", vt(140))),
            type = PlaceType.SETTLEMENT,
            developmentClass = DevelopmentClass.TOWN,
            placeIdentityKey = "p2",
            placeBudgetClass = PlaceBudgetClass.ADMINISTRATIVE_SETTLEMENT,
            locationResolution = LocationResolution.CANDIDATE_REGION,
            candidateRegions = listOf(region),
            uncertaintyRadius = 30.0,
            time = vt(140),
            confidence = Confidence.RECONSTRUCTED,
        )
        assertTrue(uncertain.showsReconstructionBadge)
        assertTrue(!place("p3").showsReconstructionBadge)

        // 결정적 재구성 배치는 admissibleRegion 없이는 만들 수 없다 (근거 없는 점 금지).
        assertFailsWith<IllegalArgumentException> {
            ScenarioPlacement(
                scenarioId = "s189",
                physicalPlaceId = "p2",
                reconstructionId = "recon-1",
                playableAnchor = GeoPoint(0.3, 0.3, "p1"),
                placementMode = PlacementMode.DETERMINISTIC_RECONSTRUCTION,
            )
        }
        ScenarioPlacement(
            scenarioId = "s189",
            physicalPlaceId = "p2",
            reconstructionId = "recon-1",
            playableAnchor = GeoPoint(0.3, 0.3, "p1"),
            admissibleRegion = region,
            placementMode = PlacementMode.DETERMINISTIC_RECONSTRUCTION,
        )
    }

    @Test
    fun `administrative change fold priority is create through retire`() {
        assertEquals(
            listOf(10, 20, 30, 40, 50, 60, 70),
            AdministrativeChangeType.entries.map { it.priority },
        )
        assertFailsWith<IllegalArgumentException> {
            AdministrativeChange(
                id = "c1",
                type = AdministrativeChangeType.RENAME,
                effectiveFrom = d(189),
                dependsOnChangeIds = listOf("c1"),
                subjectUnitIds = listOf("u1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AdministrativeChange(id = "c2", type = AdministrativeChangeType.CREATE, effectiveFrom = d(189))
        }
    }

    @Test
    fun `administrative unit cannot parent itself and needs a name`() {
        assertFailsWith<IllegalArgumentException> {
            TemporalAdministrativeUnit(
                id = "u1",
                names = listOf(TemporalName("u1", vt(140))),
                level = AdministrativeLevel.COUNTY,
                parentUnitId = "u1",
                time = vt(140),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TemporalAdministrativeUnit(
                id = "u2",
                names = emptyList(),
                level = AdministrativeLevel.COUNTY,
                parentUnitId = null,
                time = vt(140),
            )
        }
    }

    @Test
    fun `place control needs at least one authority scope`() {
        assertFailsWith<IllegalArgumentException> {
            PlaceControl(
                id = "pc1",
                physicalPlaceId = "p1",
                controllerActorId = "actor-1",
                time = vt(189),
            )
        }
        val control = PlaceControl(
            id = "pc2",
            physicalPlaceId = "p1",
            controllerActorId = "actor-1",
            authorityScopes = setOf(AuthorityScope.MILITARY, AuthorityScope.FISCAL),
            time = vt(189),
        )
        assertEquals(2, control.authorityScopes.size)
    }

    @Test
    fun `valid time is half open and rejects inverted ranges`() {
        assertFailsWith<IllegalArgumentException> { ValidTime(d(200), d(140)) }
        assertFailsWith<IllegalArgumentException> { ValidTime(d(200), d(200)) }
        assertTrue(!vt(140, 189).overlaps(vt(189, 220)))
        assertTrue(vt(140, 190).overlaps(vt(189, 220)))
        assertTrue(vt(140, 189).contains(d(188)))
        assertTrue(!vt(140, 189).contains(d(189)))
    }

    @Test
    fun `requireNoViolations throws with every violation listed`() {
        val error = assertFailsWith<IllegalArgumentException> {
            requireNoViolations(listOf("a", "b"))
        }
        assertTrue(error.message!!.contains("(2)"), error.message!!)
    }
}
