package opensamguk.engine.boot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Keep frozen scenario tests explicit without changing the committed scenario resources. */
internal object LegacySammoScenarioFixture {
    fun write(code: String, directory: Path, json: String): Path {
        val scenario = Json.parseToJsonElement(json) as JsonObject
        val withProfile = if ("ruleProfile" in scenario) scenario else JsonObject(
            linkedMapOf("ruleProfile" to JsonPrimitive("SAMMO")) + scenario,
        )
        return Files.writeString(directory.resolve("$code.json"), withProfile.toString())
    }
}
