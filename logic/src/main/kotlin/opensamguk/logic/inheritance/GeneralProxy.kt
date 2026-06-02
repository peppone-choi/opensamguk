package opensamguk.logic.inheritance

interface GeneralProxy {
    val id: Int
    val owner: Int
    val npc: Int
    fun getVar(key: String): Int
    fun getRankVar(key: String): Int
    fun getAuxVar(key: String): Any?
}
