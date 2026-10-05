package opensamguk.engine.boot

import opensamguk.infra.seed.SelectedSourceUnavailable
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.sql.DataSource

/** The one candidate database named by the fixed child installation. */
internal object D101SeedCandidateDataSource {
    fun open(database: D101SeedInstallationOriginal.Database): DataSource = try {
        val passwordWire = D101SeedInstallationOriginal.readFixedFile(database.passwordFile, 4096)
        val password = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(passwordWire)).toString()
        if (password.isEmpty() || password.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            throw SelectedSourceUnavailable()
        }
        DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = "jdbc:postgresql://${database.host}:${database.port}/${database.databaseName}"
            username = database.databaseUser
            this.password = password
        }
    } catch (_: Exception) { throw SelectedSourceUnavailable() }
}
