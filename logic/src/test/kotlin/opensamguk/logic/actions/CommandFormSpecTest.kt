package opensamguk.logic.actions

import opensamguk.logic.stats.GeneralActionPipeline
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandFormSpecTest {
    private val registry = CommandRegistry(GeneralActionPipeline())

    @Test
    fun `all 92 unique PHP command definitions retain ordered form fields`() {
        val codes = registryKeys()
        assertEquals(92, codes.size)

        val failures = codes.mapNotNull { code ->
            val definition = registry.resolve(code)
            val schemaNames = definition.argsSchema.keys.toList()
            val formNames = definition.formSpec.fields.map { it.name }
            when {
                definition.key != code -> "$code resolved to ${definition.key}"
                schemaNames != formNames -> "$code schema=$schemaNames form=$formNames"
                definition.formSpec.fields.any { it.control.isBlank() || it.valueType.isBlank() } ->
                    "$code contains an incomplete field"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `PHP custom processing forms keep their compound payload order`() {
        val expected = linkedMapOf(
            "che_군량매매" to listOf("buyRice", "amount"),
            "che_숙련전환" to listOf("srcArmType", "destArmType"),
            "che_발령" to listOf("destGeneralID", "destCityID"),
            "che_불가침제의" to listOf("destNationID", "year", "month"),
            "che_국기변경" to listOf("colorType"),
            "che_국호변경" to listOf("nationName"),
            "che_몰수" to listOf("isGold", "amount", "destGeneralID"),
            "che_물자원조" to listOf("destNationID", "amountList"),
            "che_피장파장" to listOf("destNationID", "commandType"),
            "che_포상" to listOf("isGold", "amount", "destGeneralID"),
            "che_헌납" to listOf("isGold", "amount"),
            "che_장비매매" to listOf("itemType", "itemCode"),
            "che_증여" to listOf("isGold", "amount", "destGeneralID"),
        )

        assertEquals(
            expected,
            expected.keys.associateWithTo(linkedMapOf()) { code ->
                registry.resolve(code).formSpec.fields.map { it.name }
            },
        )
    }

    private fun registryKeys(): List<String> {
        val source = Files.readString(repoRoot().resolve("logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt"))
        val registered = Regex("\"((?:che|cr|event)_[^\"]+)\"\\s*->")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()
            .distinct()
        return listOf(RestAction.key) + registered
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repo root")
        }
        return path
    }
}
