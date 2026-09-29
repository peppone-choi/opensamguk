package opensamguk.common.rng

import kotlin.math.floor

fun serializeSeed(vararg values: Any): String =
    values.joinToString("|") { v ->
        when (v) {
            is String -> "str(${v.length},$v)"
            is Int    -> "int($v)"
            is Long   -> "int($v)"
            is Double -> "int(${floor(v).toLong()})"
            is Float  -> "int(${floor(v.toDouble()).toLong()})"
            else      -> throw IllegalArgumentException("Unsupported seed value: $v")
        }
    }
