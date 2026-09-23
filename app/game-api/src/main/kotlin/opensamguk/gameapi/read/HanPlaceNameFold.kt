package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 지명 대조용 정규화 — `tools/map/audit_county_coverage.py` 의 `load_fold_table()` · `make_normalizer()` 를
 * 그대로 옮긴 것이다. 글자표는 `data/curated/han/han-name-simplification-v1.json`(빌드가 classpath `hwiha/` 로 싣는다).
 *
 * 규칙(표 파일의 `normalizationRule`):
 * - 郡([group]): 접미사를 떼지 않고 글자만 繁→簡 으로 눕힌다.
 * - 縣: 2자 접미사(侯国·侯國·属国·屬國·公国·公國) 하나를, 없으면 끝의 县·縣 하나를 떼고 눕힌다.
 *   **國·道·邑·郡·尹 은 이름의 일부다**(安國·夷道·安邑) — 떼면 조용히 어긋난다.
 *
 * 표는 「실제로 쓰인 글자만」 싣는다. 표에 없는 글자(예: 溫)는 접히지 않으므로 그런 縣은 대조에서
 * 빠진다 — 그때 호출부는 모른다고 답해야 한다.
 */
class HanPlaceNameFold internal constructor(private val table: Map<Int, Int>) {
    fun group(name: String?): String = fold((name ?: "").trim())

    fun county(name: String?): String {
        var text = (name ?: "").trim()
        val group = GROUP_SUFFIXES.firstOrNull { text.endsWith(it) && text.length > it.length }
        text = when {
            group != null -> text.dropLast(group.length)
            COUNTY_SUFFIXES.any { text.endsWith(it) } && text.length > 1 -> text.dropLast(1)
            else -> text
        }
        return fold(text)
    }

    private fun fold(text: String): String = buildString {
        text.codePoints().forEach { appendCodePoint(table[it] ?: it) }
    }

    companion object {
        const val RESOURCE = "hwiha/han-name-simplification-v1.json"
        private val GROUP_SUFFIXES = listOf("侯国", "侯國", "属国", "屬國", "公国", "公國")
        private val COUNTY_SUFFIXES = listOf("县", "縣")

        fun loadDefault(objectMapper: ObjectMapper): HanPlaceNameFold = load(objectMapper,
            checkNotNull(HanPlaceNameFold::class.java.classLoader.getResourceAsStream(RESOURCE)) {
                "Han place-name fold table resource is missing: $RESOURCE"
            }.use { it.readBytes() })

        /** 표 + 검토된 異體字 쌍. 한 글자 → 다른 한 글자, 표와 어긋남·사슬·witness 누락은 거부한다(파이썬과 같은 검증). */
        fun load(objectMapper: ObjectMapper, bytes: ByteArray): HanPlaceNameFold {
            val root = objectMapper.readTree(bytes)
            check(root.path("tableId").asText() == "han-name-simplification-v1") { "Unexpected fold table" }
            val table = linkedMapOf<Int, Int>()
            root.path("table").fields().forEach { (src, dst) -> table[single(src)] = single(dst.asText()) }
            val additions = root.path("reviewedVariantAdditions").toList()
            additions.forEach { row ->
                val src = row.path("from").asText(); val dst = row.path("to").asText()
                val from = single(src); val to = single(dst)
                require(from != to) { "reviewedVariantAdditions must map to a different character: $src" }
                require((table[from] ?: to) == to) { "reviewedVariantAdditions disagrees with the table: $src" }
                require(row.path("witness").asText().isNotBlank()) { "reviewedVariantAdditions needs a witness: $src" }
                table[from] = to
            }
            additions.forEach { row ->
                require(single(row.path("to").asText()) !in table) { "reviewedVariantAdditions chains at ${row.path("to").asText()}" }
            }
            return HanPlaceNameFold(table)
        }

        private fun single(text: String): Int {
            require(text.codePointCount(0, text.length) == 1) { "fold table entries must be single characters: $text" }
            return text.codePointAt(0)
        }
    }
}
