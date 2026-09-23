package opensamguk.logic.content

/** The four existing equipment positions are shared with attached treasure cards. */
enum class TreasureSlot { HORSE, WEAPON, BOOK, ITEM }

data class HwihaTreasureDefinition(
    val header: CardHeader,
    val sourceCode: String,
    val slot: TreasureSlot,
    /** Null means issuance is unresolved, including legacy availability 2 before #788's decision. */
    val issuedCopies: Int?,
) {
    init {
        require(header.kind == CardKind.TREASURE && header.availability == CardAvailability.UNIQUE)
        require(header.renownCost == 0 && sourceCode.isNotBlank())
        require(issuedCopies == null || issuedCopies > 0)
    }
}

data class TreasurePerson(
    val generalId: Int,
    val nationId: Int,
    val provinceId: String?,
    val npc: Boolean,
    /** Only a directly held NPC may carry its holder's treasure. */
    val directHolderId: Int? = null,
) {
    init {
        require(generalId > 0 && nationId >= 0)
        require(directHolderId == null || directHolderId != generalId)
    }
}

data class TreasureInstance(
    val instanceId: String,
    val cardId: String,
    /** The controlling general owns the card even when a direct NPC subordinate carries it. */
    val ownerGeneralId: Int,
    /** Null is the owner's unattached inventory. */
    val bearerGeneralId: Int? = null,
) {
    init {
        require(instanceId.isNotBlank() && cardId.isNotBlank() && ownerGeneralId > 0)
    }
}

/**
 * Pure, replayable ownership boundary. An engine command must persist the returned state in one
 * recorder/flush transaction; this class does not mint cards or alter the legacy equipment slots.
 */
data class HwihaTreasureState(
    val people: List<TreasurePerson>,
    val definitions: List<HwihaTreasureDefinition>,
    val instances: List<TreasureInstance>,
    val occupiedEquipmentSlots: Map<Int, Set<TreasureSlot>> = emptyMap(),
) {
    init {
        require(people.map { it.generalId }.distinct().size == people.size)
        require(definitions.map { it.header.id }.distinct().size == definitions.size)
        require(instances.map { it.instanceId }.distinct().size == instances.size) { "treasure instance consumed twice" }
        val byPerson = people.associateBy { it.generalId }
        val byCard = definitions.associateBy { it.header.id }
        require(occupiedEquipmentSlots.keys.all { it in byPerson })
        people.forEach { person ->
            person.directHolderId?.let { holderId ->
                val holder = requireNotNull(byPerson[holderId]) { "dangling direct holder" }
                require(person.npc && holder.nationId == person.nationId)
            }
        }
        val slotKeys = mutableSetOf<Pair<Int, TreasureSlot>>()
        instances.forEach { instance ->
            val definition = requireNotNull(byCard[instance.cardId]) { "dangling treasure card" }
            require(definition.issuedCopies != null) { "card issuance is undecided" }
            val owner = requireNotNull(byPerson[instance.ownerGeneralId]) { "dangling treasure owner" }
            instance.bearerGeneralId?.let { bearerId ->
                val bearer = requireNotNull(byPerson[bearerId]) { "dangling treasure bearer" }
                require(bearer.nationId == owner.nationId)
                require(bearerId == owner.generalId || (bearer.npc && bearer.directHolderId == owner.generalId)) {
                    "treasure bearer is not controlled by its owner"
                }
                require(!owner.provinceId.isNullOrBlank() && owner.provinceId == bearer.provinceId) {
                    "treasure owner and bearer must share a known province"
                }
                require(slotKeys.add(bearerId to definition.slot)) { "treasure slot already occupied" }
                require(definition.slot !in occupiedEquipmentSlots[bearerId].orEmpty()) {
                    "equipment occupies treasure slot"
                }
            }
        }
        definitions.forEach { definition ->
            if (definition.issuedCopies != null) {
                require(instances.count { it.cardId == definition.header.id } <= definition.issuedCopies)
            }
        }
    }

    /** Attach to the owner's own person card or a directly held NPC in the same province. */
    fun attach(instanceId: String, ownerId: Int, bearerId: Int): HwihaTreasureState {
        val instance = ownedInstance(instanceId, ownerId)
        require(instance.bearerGeneralId == null) { "detach before attaching elsewhere" }
        require(sameKnownProvince(ownerId, bearerId)) { "treasure attachment requires co-location" }
        return replace(instance.copy(bearerGeneralId = bearerId))
    }

    fun detach(instanceId: String, ownerId: Int): HwihaTreasureState {
        val instance = ownedInstance(instanceId, ownerId)
        require(instance.bearerGeneralId != null)
        return replace(instance.copy(bearerGeneralId = null))
    }

    /** Voluntary movement is inventory-to-inventory between co-located people of one nation. */
    fun transfer(instanceId: String, fromOwnerId: Int, toOwnerId: Int): HwihaTreasureState {
        val instance = ownedInstance(instanceId, fromOwnerId)
        require(fromOwnerId != toOwnerId && instance.bearerGeneralId == null)
        val from = person(fromOwnerId)
        val to = person(toOwnerId)
        require(from.nationId == to.nationId && sameKnownProvince(fromOwnerId, toOwnerId))
        return replace(instance.copy(ownerGeneralId = toOwnerId))
    }

    /** Explicit battle seizure: the defeated owner or bearer loses one card; the victor inventories it. */
    fun seize(instanceId: String, defeatedGeneralId: Int, victorGeneralId: Int): HwihaTreasureState {
        val instance = instances.single { it.instanceId == instanceId }
        require(defeatedGeneralId == (instance.bearerGeneralId ?: instance.ownerGeneralId))
        require(victorGeneralId != instance.ownerGeneralId)
        require(sameKnownProvince(defeatedGeneralId, victorGeneralId))
        return replace(instance.copy(ownerGeneralId = victorGeneralId, bearerGeneralId = null))
    }

    private fun ownedInstance(instanceId: String, ownerId: Int): TreasureInstance =
        instances.single { it.instanceId == instanceId }.also { require(it.ownerGeneralId == ownerId) }

    private fun person(id: Int): TreasurePerson = people.single { it.generalId == id }

    private fun sameKnownProvince(first: Int, second: Int): Boolean {
        val a = person(first).provinceId
        return !a.isNullOrBlank() && a == person(second).provinceId
    }

    private fun replace(updated: TreasureInstance): HwihaTreasureState = copy(
        instances = instances.map { if (it.instanceId == updated.instanceId) updated else it })
}
