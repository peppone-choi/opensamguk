package opensamguk.logic.battle.realtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Inflater
import java.util.zip.DeflaterOutputStream

/** Versioned, bounded checkpoint body. The authority hash is TacticalBattle.stateHash, not the zlib bytes. */
object TacticalStateCodec {
    private const val MAGIC = 0x57545331 // WTS1
    private const val MAX_COMPRESSED = 65_536
    private const val MAX_PLAIN = 65_536
    private const val MAX_UNITS = 12

    fun encode(state: TacticalState): ByteArray {
        require(state.units.size <= MAX_UNITS)
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { out ->
            out.writeInt(MAGIC)
            out.writeLong(state.seed)
            out.writeInt(state.battlefield.id)
            out.writeByte(if (state.battlefield.kind == "FIELD") 0 else 1)
            state.battlefield.rows.forEach { row -> out.write(row.toByteArray(Charsets.US_ASCII)) }
            out.writeInt(state.tick)
            out.writeInt(state.gateRow ?: -1)
            out.writeInt(state.gateCol ?: -1)
            out.writeInt(state.gateHp)
            out.writeInt(state.outcome?.ordinal ?: -1)
            out.writeInt(state.humanSides.fold(0) { mask, side -> mask or (1 shl side.ordinal) })
            out.writeInt(state.units.size)
            state.units.forEach { unit ->
                out.writeByte(unit.side.ordinal)
                out.writeByte(unit.slot.ordinal)
                out.writeInt(unit.retinue.id)
                val general = unit.retinue.general
                out.writeInt(general.id)
                out.writeByte(general.leadership)
                out.writeByte(general.strength)
                out.writeByte(general.intelligence)
                out.writeByte(general.politics)
                out.writeByte(general.charisma)
                out.writeInt(unit.retinue.troops)
                out.writeByte(unit.retinue.kind.ordinal)
                out.writeByte(unit.retinue.training)
                out.writeByte(unit.retinue.morale)
                out.writeByte(unit.retinue.fatigue)
                out.writeByte(unit.retinue.supply)
                out.writeBoolean(unit.retinue.accompaniesCorps)
                out.writeByte(unit.row)
                out.writeByte(unit.col)
                out.writeInt(unit.troops)
                out.writeShort(unit.morale)
                out.writeByte(unit.order.ordinal)
                out.writeByte(unit.rally.ordinal)
                out.writeInt(unit.moveWait)
                out.writeInt(unit.attackWait)
                out.writeBoolean(unit.escaped)
            }
        }
        require(plain.size() <= MAX_PLAIN)
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(plain.toByteArray()) }
        return compressed.toByteArray().also { require(it.size <= MAX_COMPRESSED) }
    }

    fun decode(compressed: ByteArray, expectedHash: String): TacticalState {
        require(compressed.isNotEmpty() && compressed.size <= MAX_COMPRESSED)
        require(expectedHash.matches(Regex("[0-9a-f]{64}")))
        val plain = ByteArrayOutputStream()
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val chunk = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                require(count > 0) { "battle checkpoint compressed body incomplete" }
                require(plain.size() + count <= MAX_PLAIN) { "battle checkpoint exceeds codec limit" }
                plain.write(chunk, 0, count)
            }
            require(inflater.remaining == 0) { "battle checkpoint has trailing compressed bytes" }
        } finally {
            inflater.end()
        }
        val source = ByteArrayInputStream(plain.toByteArray())
        val state = DataInputStream(source).use { input ->
            require(input.readInt() == MAGIC) { "battle checkpoint version mismatch" }
            val seed = input.readLong()
            val fieldId = input.readInt()
            val kind = when (input.readUnsignedByte()) {
                0 -> "FIELD"
                1 -> "FORTRESS"
                else -> error("battle checkpoint field kind invalid")
            }
            val rows = List(64) {
                val row = ByteArray(64)
                input.readFully(row)
                String(row, Charsets.US_ASCII)
            }
            val field = Battlefield(fieldId, kind, rows)
            val tick = input.readInt()
            val gateRow = input.readInt().takeUnless { it == -1 }
            val gateCol = input.readInt().takeUnless { it == -1 }
            val gateHp = input.readInt()
            val outcome = enumOrNull<BattleOutcome>(input.readInt())
            val sideMask = input.readInt()
            require(sideMask and 0b11.inv() == 0)
            val humanSides = BattleSide.entries.filterTo(linkedSetOf()) {
                (sideMask and (1 shl it.ordinal)) != 0
            }
            val count = input.readInt()
            require(count in 0..MAX_UNITS)
            val units = List(count) {
                val side = enumAt<BattleSide>(input.readUnsignedByte())
                val slot = enumAt<FormationSlot>(input.readUnsignedByte())
                val id = input.readInt()
                val general = GeneralStats(input.readInt(), input.readUnsignedByte(),
                    input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte(),
                    input.readUnsignedByte())
                val troops = input.readInt()
                val kindOfUnit = enumAt<UnitKind>(input.readUnsignedByte())
                val retinue = Retinue(id, general, troops, kindOfUnit, input.readUnsignedByte(),
                    input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte(),
                    input.readBoolean())
                TacticalUnit(side, slot, retinue, input.readUnsignedByte(), input.readUnsignedByte(),
                    input.readInt(), input.readUnsignedShort(), enumAt(input.readUnsignedByte()),
                    enumAt(input.readUnsignedByte()), input.readInt(), input.readInt(), input.readBoolean())
            }
            TacticalState(seed, field, tick, units, humanSides, gateRow, gateCol, gateHp, outcome)
        }
        require(source.available() == 0) { "battle checkpoint has trailing bytes" }
        require(TacticalBattle.stateHash(state) == expectedHash) { "battle checkpoint state hash mismatch" }
        return state
    }

    private inline fun <reified T : Enum<T>> enumAt(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal) ?: error("battle checkpoint enum invalid")

    private inline fun <reified T : Enum<T>> enumOrNull(ordinal: Int): T? =
        if (ordinal == -1) null else enumAt(ordinal)
}
