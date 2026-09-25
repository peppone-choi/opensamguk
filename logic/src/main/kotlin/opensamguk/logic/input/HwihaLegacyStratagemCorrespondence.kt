package opensamguk.logic.input

data class HwihaLegacyStratagemCorrespondence(val inputId: String, val legacyCommand: String,
    val cardName: String)

/** One legacy command per row, all routed through the eventual card-play executor. */
object HwihaLegacyStratagemCorrespondences {
    val rows = listOf(
        HwihaLegacyStratagemCorrespondence("stratagem.rumor", "che_선동", "유언"),
        HwihaLegacyStratagemCorrespondence("stratagem.steal", "che_탈취", "탈취"),
        HwihaLegacyStratagemCorrespondence("stratagem.sabotage", "che_파괴", "파괴"),
        HwihaLegacyStratagemCorrespondence("stratagem.fire", "che_화계", "화계"),
        HwihaLegacyStratagemCorrespondence("stratagem.lastStand", "che_필사즉생", "필사즉생"),
        HwihaLegacyStratagemCorrespondence("stratagem.mobilizePeople", "che_백성동원", "백성동원"),
        HwihaLegacyStratagemCorrespondence("stratagem.flood", "che_수몰", "수공"),
        HwihaLegacyStratagemCorrespondence("stratagem.falseReport", "che_허보", "허보"),
        HwihaLegacyStratagemCorrespondence("stratagem.raiseMilitia", "che_의병모집", "의병모집"),
        HwihaLegacyStratagemCorrespondence("stratagem.provokeRivalry", "che_이호경식", "이호경식"),
        HwihaLegacyStratagemCorrespondence("stratagem.raid", "che_급습", "급습"),
        HwihaLegacyStratagemCorrespondence("stratagem.reciprocity", "che_피장파장", "피장파장"),
    )

    /** Empty means every STRATAGEM ledger row has exactly one matching correspondence. */
    fun mismatches(catalog: HwihaInputCatalog, proposed: List<HwihaLegacyStratagemCorrespondence> = rows): List<String> {
        val actual = catalog.entries.filter { it.kind == InputKind.STRATAGEM }
            .flatMap { row -> row.legacyCommands.map { row.inputId to it } }
        val expected = proposed.map { it.inputId to it.legacyCommand }
        return buildList {
            addAll((actual - expected.toSet()).map { "unmapped:${it.first}/${it.second}" })
            addAll((expected - actual.toSet()).map { "stale:${it.first}/${it.second}" })
            addAll(expected.groupingBy { it }.eachCount().filterValues { it != 1 }.keys.map { "duplicate:${it.first}/${it.second}" })
            addAll(proposed.filter { it.cardName.isBlank() }.map { "unnamed:${it.inputId}" })
        }
    }
}
