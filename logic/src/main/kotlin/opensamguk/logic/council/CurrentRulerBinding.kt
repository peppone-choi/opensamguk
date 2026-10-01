package opensamguk.logic.council

import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.PoliticalInput
import opensamguk.logic.input.RetireInput

/** 실제 정치 입력 영수증과 함께 저장한 군주 신원. 직함/수뇌부 수치는 이 근거를 대신하지 않는다. */
data class CurrentRulerBinding(val generalId: Int, val revision: String, val sourceInputId: String) {
    init {
        require(generalId > 0)
        require(revision.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(sourceInputId in setOf(PoliticalInput.RISE, PoliticalInput.INDEPENDENCE, PoliticalInput.ABDICATE, RetireInput.INPUT_ID))
    }

    fun agreesWith(generalId: Int, nationId: Int, expectedNationId: Int, npcState: Int,
                   generalMeta: Map<String, Any?>): Boolean =
        generalId == this.generalId && nationId == expectedNationId && nationId > 0 && npcState != 5 &&
            LordStatus.read(generalMeta)

    companion object {
        const val META_KEY = "currentRulerBinding"

        fun read(meta: Map<String, Any?>): CurrentRulerBinding? {
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(row.keys == setOf("generalId", "revision", "sourceInputId"))
            val id = (row["generalId"] as? Number)?.let { number ->
                val parsed = number.toString().toIntOrNull() ?: invalid()
                parsed
            } ?: invalid()
            return CurrentRulerBinding(id, row["revision"] as? String ?: invalid(),
                row["sourceInputId"] as? String ?: invalid())
        }

        fun with(meta: Map<String, Any?>, generalId: Int, requestId: String, inputId: String): Map<String, Any?> {
            val binding = CurrentRulerBinding(generalId, requestId, inputId)
            return LinkedHashMap(meta).apply { put(META_KEY, linkedMapOf(
                "generalId" to binding.generalId, "revision" to binding.revision, "sourceInputId" to binding.sourceInputId)) }
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("저장된 군주 신원을 확인할 수 없습니다.")
    }
}
