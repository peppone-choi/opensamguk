package com.opensam.qa

import com.opensam.controller.AccountController
import com.opensam.controller.DiplomacyController
import com.opensam.controller.GeneralController
import com.opensam.dto.BuildPoolGeneralRequest
import com.opensam.dto.UpdatePoolGeneralRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.*
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.findAnnotation

/**
 * API Contract Tests — verify endpoints added in the last commit (pool/icon/diplomacy)
 * exist and DTOs have correct fields.
 *
 * These use reflection rather than mocking the full Spring context, which keeps
 * the tests lightweight while guaranteeing the controller signatures match expectations.
 */
@DisplayName("API Contract Tests")
class ApiContractTest {

    // ──────────────────────────────────────────────────
    // Pool endpoints (GeneralController)
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("GeneralController - pool endpoints")
    inner class GeneralPoolEndpoints {

        @Test
        @DisplayName("POST /worlds/{worldId}/pool endpoint exists")
        fun `buildPoolGeneral endpoint exists`() {
            val fn = GeneralController::class.memberFunctions
                .firstOrNull { it.name == "buildPoolGeneral" }
            assertNotNull(fn, "buildPoolGeneral method must exist in GeneralController")
            val annotation = fn!!.findAnnotation<PostMapping>()
            assertNotNull(annotation, "buildPoolGeneral must have @PostMapping")
        }

        @Test
        @DisplayName("PUT /worlds/{worldId}/pool/{generalId} endpoint exists")
        fun `updatePoolGeneral endpoint exists`() {
            val fn = GeneralController::class.memberFunctions
                .firstOrNull { it.name == "updatePoolGeneral" }
            assertNotNull(fn, "updatePoolGeneral method must exist in GeneralController")
            val annotation = fn!!.findAnnotation<PutMapping>()
            assertNotNull(annotation, "updatePoolGeneral must have @PutMapping")
        }

        @Test
        @DisplayName("BuildPoolGeneralRequest has required fields")
        fun `BuildPoolGeneralRequest has correct fields`() {
            val props = BuildPoolGeneralRequest::class.members.map { it.name }
            assertTrue("name" in props, "BuildPoolGeneralRequest must have 'name'")
            assertTrue("leadership" in props, "BuildPoolGeneralRequest must have 'leadership'")
            assertTrue("strength" in props, "BuildPoolGeneralRequest must have 'strength'")
            assertTrue("intel" in props, "BuildPoolGeneralRequest must have 'intel'")
            assertTrue("politics" in props, "BuildPoolGeneralRequest must have 'politics'")
            assertTrue("charm" in props, "BuildPoolGeneralRequest must have 'charm'")
        }

        @Test
        @DisplayName("UpdatePoolGeneralRequest has required fields (no name field)")
        fun `UpdatePoolGeneralRequest has correct fields without name`() {
            val props = UpdatePoolGeneralRequest::class.members.map { it.name }
            assertFalse("name" in props, "UpdatePoolGeneralRequest must NOT have 'name'")
            assertTrue("leadership" in props, "UpdatePoolGeneralRequest must have 'leadership'")
            assertTrue("strength" in props, "UpdatePoolGeneralRequest must have 'strength'")
            assertTrue("intel" in props, "UpdatePoolGeneralRequest must have 'intel'")
            assertTrue("politics" in props, "UpdatePoolGeneralRequest must have 'politics'")
            assertTrue("charm" in props, "UpdatePoolGeneralRequest must have 'charm'")
        }

        @Test
        @DisplayName("BuildPoolGeneralRequest default stats are 70")
        fun `BuildPoolGeneralRequest defaults are 70`() {
            val req = BuildPoolGeneralRequest(name = "테스트")
            assertEquals(70, req.leadership.toInt())
            assertEquals(70, req.strength.toInt())
            assertEquals(70, req.intel.toInt())
            assertEquals(70, req.politics.toInt())
            assertEquals(70, req.charm.toInt())
        }
    }

    // ──────────────────────────────────────────────────
    // Icon endpoints (AccountController)
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("AccountController - icon endpoints")
    inner class AccountIconEndpoints {

        @Test
        @DisplayName("POST /account/icon endpoint exists")
        fun `uploadIcon endpoint exists`() {
            val fn = AccountController::class.memberFunctions
                .firstOrNull { it.name == "uploadIcon" }
            assertNotNull(fn, "uploadIcon method must exist in AccountController")
            val annotation = fn!!.findAnnotation<PostMapping>()
            assertNotNull(annotation, "uploadIcon must have @PostMapping")
        }

        @Test
        @DisplayName("DELETE /account/icon endpoint exists")
        fun `deleteIcon endpoint exists`() {
            val fn = AccountController::class.memberFunctions
                .firstOrNull { it.name == "deleteIcon" }
            assertNotNull(fn, "deleteIcon method must exist in AccountController")
            val annotation = fn!!.findAnnotation<DeleteMapping>()
            assertNotNull(annotation, "deleteIcon must have @DeleteMapping")
        }

        @Test
        @DisplayName("POST /account/icon/sync endpoint exists")
        fun `syncIcon endpoint exists`() {
            val fn = AccountController::class.memberFunctions
                .firstOrNull { it.name == "syncIcon" }
            assertNotNull(fn, "syncIcon method must exist in AccountController")
            val annotation = fn!!.findAnnotation<PostMapping>()
            assertNotNull(annotation, "syncIcon must have @PostMapping")
        }

        @Test
        @DisplayName("GET /account/detailed-info endpoint exists")
        fun `getDetailedInfo endpoint exists`() {
            val fn = AccountController::class.memberFunctions
                .firstOrNull { it.name == "getDetailedInfo" }
            assertNotNull(fn, "getDetailedInfo method must exist in AccountController")
            val annotation = fn!!.findAnnotation<GetMapping>()
            assertNotNull(annotation, "getDetailedInfo must have @GetMapping")
        }

        @Test
        @DisplayName("POST /account/buildNationCandidate endpoint exists")
        fun `buildNationCandidate endpoint exists`() {
            val fn = AccountController::class.memberFunctions
                .firstOrNull { it.name == "buildNationCandidate" }
            assertNotNull(fn, "buildNationCandidate must exist in AccountController")
        }

        @Test
        @DisplayName("POST /account/instantRetreat endpoint exists")
        fun `instantRetreat endpoint exists`() {
            val fn = AccountController::class.memberFunctions
                .firstOrNull { it.name == "instantRetreat" }
            assertNotNull(fn, "instantRetreat must exist in AccountController")
        }

        @Test
        @DisplayName("POST /account/dieOnPrestart endpoint exists")
        fun `dieOnPrestart endpoint exists`() {
            val fn = AccountController::class.memberFunctions
                .firstOrNull { it.name == "dieOnPrestart" }
            assertNotNull(fn, "dieOnPrestart must exist in AccountController")
        }
    }

    // ──────────────────────────────────────────────────
    // Diplomacy endpoints (DiplomacyController)
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("DiplomacyController - respond endpoint")
    inner class DiplomacyEndpoints {

        @Test
        @DisplayName("POST /worlds/{worldId}/diplomacy/respond endpoint exists")
        fun `respond endpoint exists`() {
            val fn = DiplomacyController::class.memberFunctions
                .firstOrNull { it.name == "respond" }
            assertNotNull(fn, "respond method must exist in DiplomacyController")
            val annotation = fn!!.findAnnotation<PostMapping>()
            assertNotNull(annotation, "respond must have @PostMapping")
        }

        @Test
        @DisplayName("DiplomacyRespondRequest has messageId, action, accept fields")
        fun `DiplomacyRespondRequest has correct fields`() {
            // DiplomacyRespondRequest is a nested data class inside DiplomacyController
            // We can verify by instantiating it directly
            val req = DiplomacyController.DiplomacyRespondRequest(
                messageId = 42L,
                action = "accept",
                accept = true,
            )
            assertEquals(42L, req.messageId)
            assertEquals("accept", req.action)
            assertTrue(req.accept)
        }

        @Test
        @DisplayName("DiplomacyRespondRequest accept=false is valid for rejection")
        fun `DiplomacyRespondRequest can represent rejection`() {
            val req = DiplomacyController.DiplomacyRespondRequest(
                messageId = 1L,
                action = "reject",
                accept = false,
            )
            assertFalse(req.accept)
            assertEquals("reject", req.action)
        }

        @Test
        @DisplayName("DiplomacyController has getRelations and getRelationsForNation endpoints")
        fun `existing diplomacy endpoints still present`() {
            val getRelationsFn = DiplomacyController::class.memberFunctions
                .firstOrNull { it.name == "getRelations" }
            assertNotNull(getRelationsFn, "getRelations must still exist")

            val getForNationFn = DiplomacyController::class.memberFunctions
                .firstOrNull { it.name == "getRelationsForNation" }
            assertNotNull(getForNationFn, "getRelationsForNation must still exist")
        }
    }
}
