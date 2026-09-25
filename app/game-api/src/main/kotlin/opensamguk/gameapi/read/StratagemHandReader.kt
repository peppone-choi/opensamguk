package opensamguk.gameapi.read

import opensamguk.logic.input.StratagemHand
import opensamguk.logic.input.StratagemCardType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class StratagemHandForbidden : RuntimeException()
data class OwnedStratagemCard(val instanceId:Int, val type:StratagemCardType, val label:String)
data class OwnedStratagemHand(val status:String, val cards:List<OwnedStratagemCard> = emptyList(),
    val handLimit:Int = StratagemHand.HAND_LIMIT, val canUse:Boolean = false)

@Service
class StratagemHandReader(private val generals:GeneralReadRepository,private val worlds:WorldStateReadRepository) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun read(generalId:Int,userId:Long):OwnedStratagemHand {
        val actor=generals.findById(generalId).orElse(null) ?: throw StratagemHandForbidden()
        if(userId<=0 || userId>Int.MAX_VALUE || actor.userId?.toLongOrNull()!=userId)throw StratagemHandForbidden()
        val world=worlds.findProcessWorld() ?: return OwnedStratagemHand("UNAVAILABLE")
        if(actor.worldId!=world.id)return OwnedStratagemHand("UNAVAILABLE")
        if(world.config["ruleProfile"]!="HWIHA")return OwnedStratagemHand("WRONG_RULE_PROFILE")
        val hand=try { StratagemHand.read(actor.meta,generalId) }
            catch (_:IllegalArgumentException) { return OwnedStratagemHand("UNAVAILABLE") }
            ?: return OwnedStratagemHand("NOT_READY")
        return OwnedStratagemHand("READY",hand.hand.map { id ->
            val type=hand.cardType(id)
            OwnedStratagemCard(id,type,when(type) {
                StratagemCardType.FORTIFY -> "견벽"
                StratagemCardType.INSIGHT -> "간파"
            })
        })
    }
}
