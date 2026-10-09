package opensamguk.gameapi.court.reward

import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.*

class RewardOptionsDependenciesTest {
    private val classes = ClassFileImporter().importPackages("opensamguk.gameapi.court.reward")
    private fun dependencies(type: Class<*>): Set<String> = classes.get(type).directDependenciesFromSelf
        .map { it.targetClass.name }.toSet()

    @Test fun `controller reaches application query without reader repository JDBC or domain shortcuts`() {
        val targets = dependencies(RewardOptionsController::class.java)
        assertTrue(RewardOptionsQuery::class.java.name in targets)
        assertFalse(targets.any { it.endsWith("Reader") || it.endsWith("Repository") ||
            it.startsWith("opensamguk.logic.") || it.startsWith("opensamguk.infra.") ||
            it.startsWith("org.springframework.jdbc.") })
    }

    @Test fun `query and projection have no engine dependency and projection has no database access`() {
        assertTrue(RewardOptionsReader::class.java.name in dependencies(RewardOptionsQuery::class.java))
        assertTrue(RewardOptionsProjection::class.java.name in dependencies(RewardOptionsQuery::class.java))
        val projection = dependencies(RewardOptionsProjection::class.java)
        assertTrue("opensamguk.logic.economy.WarehouseFundingScope" in projection)
        assertFalse(projection.any { it.endsWith("Repository") || it.startsWith("org.springframework.jdbc.") ||
            it.startsWith("jakarta.persistence.") })
        for (type in listOf(RewardOptionsController::class.java, RewardOptionsQuery::class.java,
            RewardOptionsReader::class.java, RewardOptionsProjection::class.java)) {
            assertFalse(dependencies(type).any { it.startsWith("opensamguk.engine.") })
        }
        assertFalse(dependencies(RewardOptionsReader::class.java).any {
            it.endsWith("GeneralResolver") || it.endsWith("GeneralOwnershipClassifier") ||
                it.endsWith("GeneralOwnerRepository")
        }, "the options read must not call repairing ownership paths")
    }
}
