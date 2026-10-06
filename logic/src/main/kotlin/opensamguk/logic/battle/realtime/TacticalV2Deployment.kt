package opensamguk.logic.battle.realtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

/** v2 identity is a campaign source, never a formation slot or a generated general. */
enum class TacticalV2SourceKind { RETINUE, CITY_GARRISON_BUGOK }

data class TacticalV2SourceKey(val kind: TacticalV2SourceKind, val sourceId: String,
                              val cityId: Int? = null) :
    Comparable<TacticalV2SourceKey> {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= 128 && sourceId == sourceId.trim())
        when (kind) {
            TacticalV2SourceKind.RETINUE -> require(cityId == null &&
                sourceId.toIntOrNull()?.let { it > 0 && it.toString() == sourceId } == true)
            TacticalV2SourceKind.CITY_GARRISON_BUGOK -> require(cityId != null && cityId > 0)
        }
    }

    override fun compareTo(other: TacticalV2SourceKey): Int =
        compareValuesBy(this, other, { it.kind.ordinal }, { it.cityId ?: 0 }, { it.sourceId })
}

data class TacticalV2Cell(val row: Int, val col: Int) : Comparable<TacticalV2Cell> {
    init { require(row in 0..63 && col in 0..63) }
    override fun compareTo(other: TacticalV2Cell): Int = compareValuesBy(this, other, { it.row }, { it.col })
}

/** Source facts only. Combat stats require a separately pinned, real campaign source. */
data class TacticalV2UnitSource(
    val key: TacticalV2SourceKey,
    val side: BattleSide,
    val ownerGeneralId: Int?,
    val commanderGeneralId: Int?,
    val initialTroops: Int,
    val sourceRevision: Long,
) {
    init {
        require(initialTroops > 0 && sourceRevision >= 0)
        when (key.kind) {
            TacticalV2SourceKind.RETINUE -> require(ownerGeneralId != null && ownerGeneralId > 0 &&
                commanderGeneralId != null && commanderGeneralId > 0)
            TacticalV2SourceKind.CITY_GARRISON_BUGOK -> require(side == BattleSide.DEFENDER &&
                (ownerGeneralId == null || ownerGeneralId > 0) &&
                (commanderGeneralId == null || commanderGeneralId > 0))
        }
    }

    val stationary: Boolean get() = key.kind == TacticalV2SourceKind.CITY_GARRISON_BUGOK
}

/** Every source occupies one pinned cell at tick zero; there is no six/twelve-unit cap or reserve. */
class TacticalV2Deployment(
    val boardId: Int,
    val terrainSha256: String,
    units: List<TacticalV2UnitSource>,
    cells: Map<TacticalV2SourceKey, TacticalV2Cell>,
    allowedCells: Map<BattleSide, Set<TacticalV2Cell>>,
    val revision: Long = 0,
) {
    val units: List<TacticalV2UnitSource> = Collections.unmodifiableList(ArrayList(units))
    val cells: Map<TacticalV2SourceKey, TacticalV2Cell> =
        Collections.unmodifiableMap(LinkedHashMap(cells))
    val allowedCells: Map<BattleSide, Set<TacticalV2Cell>> = Collections.unmodifiableMap(
        allowedCells.mapValuesTo(LinkedHashMap<BattleSide, Set<TacticalV2Cell>>()) { (_, value) ->
            Collections.unmodifiableSet(LinkedHashSet(value))
        })

    init {
        require(boardId in 0..213 && terrainSha256.matches(Regex("[0-9a-f]{64}")))
        require(revision >= 0 && this.units.isNotEmpty())
        require(this.units == this.units.sortedBy { it.key } &&
            this.units.map { it.key }.distinct().size == this.units.size)
        require(this.cells.keys == this.units.map { it.key }.toSet() &&
            this.cells.values.toSet().size == this.cells.size) {
            "all real sources must be deployed once on distinct cells"
        }
        require(this.allowedCells.keys == BattleSide.entries.toSet())
        require(this.allowedCells.values.all { it.size <= 64 * 64 })
        require(this.units.all { unit ->
            this.cells.getValue(unit.key) in this.allowedCells.getValue(unit.side)
        }) {
            "source is outside its pinned spawn cells"
        }
    }

    fun copy(
        boardId: Int = this.boardId,
        terrainSha256: String = this.terrainSha256,
        units: List<TacticalV2UnitSource> = this.units,
        cells: Map<TacticalV2SourceKey, TacticalV2Cell> = this.cells,
        allowedCells: Map<BattleSide, Set<TacticalV2Cell>> = this.allowedCells,
        revision: Long = this.revision,
    ) = TacticalV2Deployment(boardId, terrainSha256, units, cells, allowedCells, revision)

    override fun equals(other: Any?): Boolean = other is TacticalV2Deployment &&
        boardId == other.boardId && terrainSha256 == other.terrainSha256 &&
        units == other.units && cells == other.cells && allowedCells == other.allowedCells &&
        revision == other.revision

    override fun hashCode(): Int = listOf(boardId, terrainSha256, units, cells, allowedCells, revision).hashCode()

    override fun toString(): String = "TacticalV2Deployment(boardId=$boardId, " +
        "terrainSha256=$terrainSha256, units=$units, cells=$cells, allowedCells=$allowedCells, " +
        "revision=$revision)"

    /** A JOINING owner can move a real corps unit into an empty cell or swap with its own unit. */
    fun move(sourceKey: TacticalV2SourceKey, target: TacticalV2Cell,
             controlledKeys: Set<TacticalV2SourceKey>, expectedRevision: Long): TacticalV2Deployment {
        require(expectedRevision == revision) { "STALE_DEPLOYMENT" }
        val source = units.singleOrNull { it.key == sourceKey } ?: error("unknown battle source")
        require(sourceKey in controlledKeys && !source.stationary) { "UNAUTHORIZED" }
        require(target in allowedCells.getValue(source.side)) { "INVALID_SPAWN" }
        val previous = cells.getValue(sourceKey)
        if (previous == target) return this
        val displacedKey = cells.entries.singleOrNull { it.value == target }?.key
        if (displacedKey != null) {
            val displaced = units.single { it.key == displacedKey }
            require(displacedKey in controlledKeys && !displaced.stationary &&
                displaced.side == source.side) { "UNAUTHORIZED" }
            require(previous in allowedCells.getValue(displaced.side)) { "INVALID_SPAWN" }
        }
        val next = cells.toMutableMap()
        next[sourceKey] = target
        if (displacedKey != null) next[displacedKey] = previous
        return copy(cells = next, revision = revision + 1)
    }

    fun stateSha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(TacticalV2DeploymentCodec.encode(this))
        .joinToString("") { "%02x".format(it) }
}

/** Versioned binary pin for the dynamic v2 deployment; v1 TacticalStateCodec is untouched. */
object TacticalV2DeploymentCodec {
    private const val MAGIC = 0x42544432 // BTD2
    private const val VERSION = 2

    fun encode(value: TacticalV2Deployment): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(value.boardId)
            out.writeString(value.terrainSha256)
            out.writeLong(value.revision)
            out.writeInt(value.units.size)
            value.units.forEach { unit ->
                out.writeInt(unit.key.kind.ordinal)
                out.writeInt(unit.key.cityId ?: 0)
                out.writeString(unit.key.sourceId)
                out.writeInt(unit.side.ordinal)
                out.writeInt(unit.ownerGeneralId ?: 0)
                out.writeInt(unit.commanderGeneralId ?: 0)
                out.writeInt(unit.initialTroops)
                out.writeLong(unit.sourceRevision)
                val cell = value.cells.getValue(unit.key)
                out.writeInt(cell.row)
                out.writeInt(cell.col)
            }
            BattleSide.entries.forEach { side ->
                val cells = value.allowedCells.getValue(side).sorted()
                out.writeInt(cells.size)
                cells.forEach { cell -> out.writeInt(cell.row); out.writeInt(cell.col) }
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray, expectedBoardId: Int, expectedTerrainSha256: String,
               expectedAllowedCells: Map<BattleSide, Set<TacticalV2Cell>>): TacticalV2Deployment =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val boardId = input.readInt()
            val terrainSha = input.readString()
            require(boardId == expectedBoardId && terrainSha == expectedTerrainSha256) {
                "battlefield pin mismatch"
            }
            val revision = input.readLong()
            val count = input.readInt()
            require(count in 1..(64 * 64)) { "invalid physical unit count" }
            val units = ArrayList<TacticalV2UnitSource>(count)
            val positions = linkedMapOf<TacticalV2SourceKey, TacticalV2Cell>()
            repeat(count) {
                val kind = TacticalV2SourceKind.entries.getOrNull(input.readInt())
                    ?: error("invalid source kind")
                val cityId = input.readInt().takeIf { it != 0 }
                val key = TacticalV2SourceKey(kind, input.readString(), cityId)
                val side = BattleSide.entries.getOrNull(input.readInt()) ?: error("invalid battle side")
                val owner = input.readInt().takeIf { it != 0 }
                val commander = input.readInt().takeIf { it != 0 }
                units += TacticalV2UnitSource(key, side, owner, commander, input.readInt(), input.readLong())
                require(positions.put(key, TacticalV2Cell(input.readInt(), input.readInt())) == null) {
                    "duplicate battle source"
                }
            }
            val allowed = BattleSide.entries.associateWith {
                val size = input.readInt()
                require(size in 0..(64 * 64))
                val ordered = List(size) { TacticalV2Cell(input.readInt(), input.readInt()) }
                require(ordered == ordered.sorted()) { "noncanonical spawn cell order" }
                ordered.toSet().also { cells ->
                    require(cells.size == size) { "duplicate spawn cell" }
                }
            }
            require(input.available() == 0 && allowed == expectedAllowedCells) {
                "battle spawn pin mismatch"
            }
            TacticalV2Deployment(boardId, terrainSha, units, positions, allowed, revision)
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..512)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 1..512)
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
    }
}
