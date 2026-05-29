package opensamguk.common.rng

import java.security.MessageDigest

/** SHA-512 digest of [input]; 64-byte output. Matches TS sha512Bytes (node:crypto / @noble/hashes). */
fun sha512(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-512").digest(input)
