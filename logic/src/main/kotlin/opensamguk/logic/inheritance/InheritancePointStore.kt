package opensamguk.logic.inheritance

import opensamguk.logic.inheritance.InheritanceKey.Companion.keyName

class InheritancePointStore {
    private val data = mutableMapOf<Int, MutableMap<String, Pair<Double, Any?>>>()

    fun get(ownerID: Int, key: String): Pair<Double, Any?>? {
        return data[ownerID]?.get(key)
    }

    fun getAll(ownerID: Int): Map<String, Pair<Double, Any?>> {
        return data[ownerID]?.toMap() ?: emptyMap()
    }

    fun set(ownerID: Int, key: String, value: Double, aux: Any?) {
        val type = InheritanceKeyRegistry[InheritanceKey.valueOf(key.uppercase())]
        if (type.storeType != true) {
            throw IllegalArgumentException("$key 는 직접 저장형 유산 포인트가 아님")
        }
        if (type.pointCoeff != 1.0 && value != 0.0) {
            throw IllegalArgumentException("$key 는 1:1 유산 포인트가 아님")
        }
        putRaw(ownerID, key, value, aux)
    }

    fun increaseRaw(ownerID: Int, key: String, value: Double, aux: Any?): Double {
        val type = InheritanceKeyRegistry[InheritanceKey.valueOf(key.uppercase())]
        if (type.storeType != true) {
            throw IllegalArgumentException("$key 는 직접 저장형 유산 포인트가 아님")
        }
        val map = data.getOrPut(ownerID) { mutableMapOf() }
        val old = map[key]
        val oldValue = if (old != null && old.second == aux) old.first else 0.0
        val newValue = oldValue + value * type.pointCoeff
        map[key] = newValue to aux
        return newValue
    }

    fun clear(ownerID: Int) {
        val map = data[ownerID] ?: return
        val previous = map[InheritanceKey.PREVIOUS.keyName()]
        map.clear()
        if (previous != null) {
            map[InheritanceKey.PREVIOUS.keyName()] = previous
        }
    }

    /** Bypass validation for computed points (merge phase). */
    fun putRaw(ownerID: Int, key: String, value: Double, aux: Any?) {
        data.getOrPut(ownerID) { mutableMapOf() }[key] = value to aux
    }
}
