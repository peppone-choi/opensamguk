package opensamguk.common.rng

/** Mirrors TS convertBytesLikeToUint8Array(data, encodeUTF8=true). Production seeds are always Strings → UTF-8. */
fun bytesLikeToByteArray(seed: String): ByteArray = seed.toByteArray(Charsets.UTF_8)

fun bytesLikeToByteArray(seed: ByteArray): ByteArray = seed.copyOf()
