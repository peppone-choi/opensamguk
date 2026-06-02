package opensamguk.logic.auction

/**
 * The 상인 (merchant) NPC host of a system/neutral auction — a NON-PERSISTED general (PHP
 * `DummyGeneral`, `no=0`, `npc=3`). Auction lifecycle short-circuits for a DummyGeneral host/bidder:
 * `refundBid`/`tryFinish`/`rollbackAuction` return early and `applyDB` is a no-op — the dummy is NEVER
 * flushed (host_general_id=0). This marker lets the resource/unique families exclude the dummy from
 * the flush channel + the Message sends.
 */
data class DummyGeneral(val id: Int = 0, val name: String = "Dummy") {
    companion object {
        /** PHP `host_general_id == 0` is the system/neutral 상인 auction host. */
        fun isDummyHost(hostGeneralId: Int): Boolean = hostGeneralId == 0
    }
}
