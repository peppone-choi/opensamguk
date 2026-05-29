package opensamguk.logic.domain

/**
 * `GetNationColors()` — verbatim port of `legacy/devsam-core/hwe/func_legacy.php:105-114`.
 *
 * The canonical flat 33-element nation-color palette, indexed by the `colorType` integer arg
 * (`nation.color = GetNationColors()[colorType]`). 거병/건국 set a fixed `#330000`; 국기변경 indexes
 * into this list. Lives in `domain` so every founding/nation command shares the SAME single source.
 */
val NATION_COLORS: List<String> = listOf(
    "#FF0000", "#800000", "#A0522D", "#FF6347", "#FFA500", "#FFDAB9", "#FFD700", "#FFFF00",
    "#7CFC00", "#00FF00", "#808000", "#008000", "#2E8B57", "#008080", "#20B2AA", "#6495ED", "#7FFFD4",
    "#AFEEEE", "#87CEEB", "#00FFFF", "#00BFFF", "#0000FF", "#000080", "#483D8B", "#7B68EE", "#BA55D3",
    "#800080", "#FF00FF", "#FFC0CB", "#F5F5DC", "#E0FFFF", "#FFFFFF", "#A9A9A9",
)

/** PHP `GetNationColors()` — returns the canonical 33-element palette. */
fun GetNationColors(): List<String> = NATION_COLORS
