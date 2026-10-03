package opensamguk.logic.creation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

class CreationAdmissionTest {
    private val policy = CreationSelectionPolicy.parse(
        checkNotNull(javaClass.classLoader.getResource("campaign/general-creation-selection-v1.json")).readText())
    private val stats = CreationAdmission.Stats(60, 60, 60, 60, 60)
    private val custom = CreationAdmission.Custom("예시 장수", 1, stats, "WANGDO", "DISCIPLINE")

    @Test fun approvedValuesAreAcceptedAndInvalidValuesAreRejected() {
        assertNull(CreationAdmission.custom(custom, policy, setOf(1)) { it.trim().takeIf(String::isNotEmpty) })
        assertEquals(CreationAdmission.Failure.INVALID_STATS,
            CreationAdmission.custom(custom.copy(stats = stats.copy(charm = 59)), policy, setOf(1)) { it })
        assertEquals(CreationAdmission.Failure.INVALID_NATIVE_COUNTY,
            CreationAdmission.custom(custom, policy, emptySet()) { it })
        assertEquals(CreationAdmission.Failure.INVALID_IDEOLOGY,
            CreationAdmission.custom(custom.copy(ideologyId = "REFERENCE_ONLY"), policy, setOf(1)) { it })
    }

    @Test fun eachStatBoundaryAndExactTotalAreEnforcedAtAdmission() {
        val validBoundary = CreationAdmission.Stats(20, 85, 65, 65, 65)
        assertNull(CreationAdmission.custom(custom.copy(stats = validBoundary), policy, setOf(1)) { it })

        val outsideRangeWithExactTotal = listOf(
            CreationAdmission.Stats(19, 85, 65, 65, 66),
            CreationAdmission.Stats(85, 19, 65, 65, 66),
            CreationAdmission.Stats(85, 65, 19, 65, 66),
            CreationAdmission.Stats(85, 65, 65, 19, 66),
            CreationAdmission.Stats(85, 65, 65, 66, 19),
            CreationAdmission.Stats(86, 20, 64, 65, 65),
            CreationAdmission.Stats(20, 86, 64, 65, 65),
            CreationAdmission.Stats(20, 64, 86, 65, 65),
            CreationAdmission.Stats(20, 64, 65, 86, 65),
            CreationAdmission.Stats(20, 64, 65, 65, 86),
        )
        for (candidate in outsideRangeWithExactTotal) {
            assertEquals(300, candidate.values().sum())
            assertEquals(CreationAdmission.Failure.INVALID_STATS,
                CreationAdmission.custom(custom.copy(stats = candidate), policy, setOf(1)) { it },
                "stats=$candidate")
        }
        for (candidate in listOf(stats.copy(charm = 59), stats.copy(charm = 61))) {
            assertEquals(CreationAdmission.Failure.INVALID_STATS,
                CreationAdmission.custom(custom.copy(stats = candidate), policy, setOf(1)) { it },
                "stats=$candidate")
        }
    }

    @Test fun missingHistoricalHometownDoesNotBecomeARejectionOrLocation() {
        val historical = CreationAdmission.Historical(127)
        val state = CreationAdmission.HistoricalState(true, true, false, true, true, null)
        assertNull(CreationAdmission.historical(historical, state))
        assertEquals(CreationAdmission.Failure.HISTORICAL_PERSON_NOT_APPEARED,
            CreationAdmission.historical(historical, state.copy(appeared = false)))
        assertEquals(CreationAdmission.Failure.HISTORICAL_PERSON_UNAVAILABLE,
            CreationAdmission.historical(historical, state.copy(alreadyClaimed = true)))
    }

    @Test fun historicalDeathYearDoesNotPreemptTheLiveCardAtTheStartOfThatYear() {
        assertEquals(true, CreationAdmission.historicalAliveInYear(190,
            mapOf("deadyear" to 190, "rtk14_death_year" to 190)))
        assertEquals(false, CreationAdmission.historicalAliveInYear(191,
            mapOf("deadyear" to 190, "rtk14_death_year" to 190)))
        assertEquals(true, CreationAdmission.historicalAliveInYear(190,
            mapOf("rtk14_death_year" to 190)))
        assertEquals(false, CreationAdmission.historicalAliveInYear(191,
            mapOf("rtk14_death_year" to 190)))
        assertEquals(true, CreationAdmission.historicalAliveInYear(190, emptyMap()))
        assertEquals(true, CreationAdmission.historicalAliveInYear(190,
            mapOf("deadyear" to 190, "rtk14_death_year" to 189)))
    }

    @Test fun worldAndPolicyChecksFailClosed() {
        val gate = CreationAdmission.Gate(1, 1, true, true, true, false, CreationKind.CUSTOM, true, true)
        assertNull(CreationAdmission.gate(gate, policy))
        assertEquals(CreationAdmission.Failure.WORLD_CHANGED,
            CreationAdmission.gate(gate.copy(expectedWorldId = 2), policy))
        assertEquals(CreationAdmission.Failure.CREATION_POLICY_UNAVAILABLE,
            CreationAdmission.gate(gate, null))
        assertEquals(CreationAdmission.Failure.INVALID_REQUEST,
            CreationAdmission.gate(gate.copy(seasonRunning = false), policy))
        assertEquals(CreationAdmission.Failure.INVALID_REQUEST,
            CreationAdmission.gate(gate.copy(sourceReady = false), policy))
        assertEquals(CreationAdmission.Failure.INVALID_REQUEST,
            CreationAdmission.gate(gate.copy(kind = CreationKind.HISTORICAL),
                policy.copy(modes = listOf(CreationModePolicy(CreationKind.CUSTOM, true),
                    CreationModePolicy(CreationKind.HISTORICAL, false)))))
    }

    @Test fun malformedSelectionPolicyCannotWidenChoices() {
        val resource = checkNotNull(javaClass.classLoader.getResource("campaign/general-creation-selection-v1.json")).readText()
        assertFailsWith<IllegalArgumentException> {
            CreationSelectionPolicy.parse(resource.replace("DISPLAY_ONLY", "ACTIVE"))
        }
        assertFailsWith<IllegalArgumentException> {
            CreationSelectionPolicy.parse(resource.replace("WANGDO", "UNKNOWN"))
        }
    }

    @Test fun confirmedNameRuleUsesCodePointsNfcAndOnlyApprovedScripts() {
        val rule = CreationNameRule.APPROVED
        assertEquals("예시 장수", rule.normalize("  예시 장수  "))
        assertEquals(rule.uniqueKey("ALEX"), rule.uniqueKey("alex"))
        assertEquals(rule.uniqueKey("Straße"), rule.uniqueKey("STRASSE"))
        assertEquals("é", rule.normalize("e\u0301")) // NFC canonical equivalent is one Latin letter.
        assertEquals("𠀀".repeat(12), rule.normalize("𠀀".repeat(12))) // supplementary Han: 12 code points.
        assertNull(rule.normalize("𠀀".repeat(13)))
        assertEquals("張A가", rule.normalize("張A가")) // approved scripts may be mixed.
        assertNull(rule.normalize("장수\n이름"))
        assertNull(rule.normalize("\n장수"))
        assertNull(rule.normalize("장수  이름"))
        assertNull(rule.normalize("장수··이름"))
        assertNull(rule.normalize("장수 ·이름"))
        assertNull(rule.normalize("장수·"))
        assertNull(rule.normalize("장수1"))
        assertNull(rule.normalize("A\u20DD")) // combining mark left after NFC.
        assertNull(rule.normalize("Αλεξ")) // Greek is outside Hangul/Han/Latin.
        assertNull(rule.normalize("abcdefghijklmn"))
        assertEquals("張·遼", rule.normalize("張·遼"))
        assertEquals(rule.uniqueKey("張·遼"), rule.collisionKeyExisting("ⓝ張·遼"))
    }

    @Test fun requestFingerprintDistinguishesChangedBodyBeforeNormalization() {
        val first = CreationRequestFingerprint.sha256(1, CreationRequestFingerprint.Choice.Custom(custom))
        val same = CreationRequestFingerprint.sha256(1, CreationRequestFingerprint.Choice.Custom(custom.copy()))
        val changed = CreationRequestFingerprint.sha256(1,
            CreationRequestFingerprint.Choice.Custom(custom.copy(name = " 예시 장수 ")))
        val otherWorld = CreationRequestFingerprint.sha256(2, CreationRequestFingerprint.Choice.Custom(custom))
        assertEquals(first, same)
        assertNotEquals(first, changed)
        assertNotEquals(first, otherWorld)
    }

    @Test fun commandRequestIdsDoNotCollideAcrossAccountsOrWorlds() {
        val uuid = "92d9244b-6eb5-4f89-971d-d1b1247e0ff6"
        val one = CreationRequestFingerprint.commandRequestId(10, 1, uuid)
        assertEquals(one, CreationRequestFingerprint.commandRequestId(10, 1, uuid))
        assertNotEquals(one, CreationRequestFingerprint.commandRequestId(11, 1, uuid))
        assertNotEquals(one, CreationRequestFingerprint.commandRequestId(10, 2, uuid))
    }
}
