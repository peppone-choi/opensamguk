package opensamguk.logic.battle.realtime

/** One column-contiguous D0–DF door object. The open record is always its +0x10 twin. */
internal data class TacticalDoorRun(
    val col: Int,
    val firstRow: Int,
    val closedRecords: List<Int>,
    val openedSolidAbove: List<Boolean>,
) {
    init {
        require(col in 0..63 && firstRow in 0..63 && closedRecords.isNotEmpty())
        require(firstRow + closedRecords.size <= 64)
        require(closedRecords.all { it in 0xD0..0xDF })
        require(openedSolidAbove.size == closedRecords.size)
    }

    val lastRow: Int get() = firstRow + closedRecords.lastIndex
}

/**
 * Ground-plane movement decoded from a pinned v2 board. The codec still needs a versioned,
 * byte-exact source format before constructing this from a committed ticket. Height/link
 * data are immutable here; only a selected door run's occupancy changes on breach.
 */
internal class TacticalGroundTraversalV2(
    groundHeight: ByteArray,
    groundLinks: ByteArray,
    groundSolidAbove: ByteArray,
    doorRuns: List<TacticalDoorRun>,
) {
    private val height = groundHeight.copyOf()
    private val links = groundLinks.copyOf()
    private val solidAbove = groundSolidAbove.copyOf()
    private val runs = doorRuns.map { run ->
        run.copy(
            closedRecords = run.closedRecords.toList(),
            openedSolidAbove = run.openedSolidAbove.toList(),
        )
    }
    private val doorAt = IntArray(4096) { -1 }
    private val doorOffset = IntArray(4096) { -1 }

    init {
        require(height.size == 4096 && links.size == 4096 && solidAbove.size == 4096)
        require(runs == runs.sortedWith(compareBy<TacticalDoorRun> { it.col }.thenBy { it.firstRow }))
        for (index in 0 until 4096) {
            require((height[index].toInt() and 0xff) in setOf(0, 1, 2, 3, 0x80))
            require(((links[index].toInt() and 0xff) and 0x0f) == 0)
            require(solidAbove[index].toInt() in 0..1)
        }
        runs.forEachIndexed { runIndex, run ->
            run.closedRecords.forEachIndexed { offset, _ ->
                val cell = (run.firstRow + offset) * 64 + run.col
                require(doorAt[cell] == -1 && (height[cell].toInt() and 0xff) != 0x80)
                doorAt[cell] = runIndex
                doorOffset[cell] = offset
            }
        }
        for (row in 0..63) for (col in 0..63) {
            val index = row * 64 + col
            val bits = links[index].toInt() and 0xff
            for ((bit, nextRow, nextCol) in listOf(
                Triple(0x10, row, col - 1), Triple(0x20, row, col + 1),
                Triple(0x40, row - 1, col), Triple(0x80, row + 1, col))) {
                if ((bits and bit) == 0) continue
                require(nextRow in 0..63 && nextCol in 0..63)
                val currentHeight = height[index].toInt() and 0xff
                val nextHeight = height[nextRow * 64 + nextCol].toInt() and 0xff
                require(currentHeight != 0x80 && nextHeight != 0x80 &&
                    kotlin.math.abs(currentHeight - nextHeight) <= 1)
            }
        }
    }

    fun passable(row: Int, col: Int, selectedDoor: Int?, breached: Boolean): Boolean {
        require(selectedDoor == null || selectedDoor in runs.indices)
        if (row !in 0..63 || col !in 0..63) return false
        val index = row * 64 + col
        val cellHeight = height[index].toInt() and 0xff
        val cellLinks = links[index].toInt() and 0xff
        if (cellHeight == 0x80 || (cellHeight == 0 && cellLinks == 0)) return false
        val door = doorAt[index]
        if (door >= 0) {
            return breached && selectedDoor == door &&
                !runs[door].openedSolidAbove[doorOffset[index]]
        }
        return solidAbove[index].toInt() == 0
    }

    fun canStep(row: Int, col: Int, nextRow: Int, nextCol: Int,
                selectedDoor: Int?, breached: Boolean): Boolean {
        if (!passable(row, col, selectedDoor, breached) ||
            !passable(nextRow, nextCol, selectedDoor, breached)) return false
        val bit = when {
            nextRow == row && nextCol == col - 1 -> 0x10
            nextRow == row && nextCol == col + 1 -> 0x20
            nextRow == row - 1 && nextCol == col -> 0x40
            nextRow == row + 1 && nextCol == col -> 0x80
            else -> return false
        }
        return (links[row * 64 + col].toInt() and bit) != 0
    }
}
