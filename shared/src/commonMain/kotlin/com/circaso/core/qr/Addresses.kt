package com.circaso.core.qr

/**
 * Whether an address is whole and unmistyped, beyond its shape: an EVM address in mixed case must match its EIP-55
 * checksum (all lower or all upper case carries none), and the zero address never pays; a Solana address must decode
 * from base58 to a 32-byte key. The backend's pay link page (`PayLinks`, share module) checks the same.
 */
object Addresses {
    private val Evm = Regex("^0x[0-9a-fA-F]{40}$")
    private val Base58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
    private const val Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun isValidEvm(address: String): Boolean {
        if (!Evm.matches(address)) return false
        val body = address.substring(2)
        if (body.all { it == '0' }) return false
        val mixed = body != body.lowercase() && body != body.uppercase()
        return !mixed || checksummed(body.lowercase()) == "0x$body"
    }

    fun isValidSolana(address: String): Boolean {
        if (!Base58.matches(address)) return false
        val bytes = ArrayList<Int>(SolanaKeyBytes)
        for (ch in address) {
            var carry = Alphabet.indexOf(ch)
            for (i in bytes.indices) {
                carry += bytes[i] * 58
                bytes[i] = carry and 0xff
                carry = carry shr 8
            }
            while (carry > 0) { bytes += carry and 0xff; carry = carry shr 8 }
        }
        return bytes.size + address.takeWhile { it == '1' }.length == SolanaKeyBytes
    }

    /** The EIP-55 form of a lower-case 40-digit address body (no `0x`). */
    fun checksummed(lower: String): String {
        val hash = Keccak256.hash(lower.encodeToByteArray())
        return buildString(42) {
            append("0x")
            lower.forEachIndexed { i, c ->
                val byte = hash[i / 2].toInt() and 0xff
                val nibble = if (i % 2 == 0) byte shr 4 else byte and 0x0f
                append(if (c.isLetter() && nibble >= 8) c.uppercaseChar() else c)
            }
        }
    }

    private const val SolanaKeyBytes = 32
}

/** Keccak-256 as Ethereum uses it (the original padding, not SHA3's): only for EIP-55 checksums here. */
internal object Keccak256 {
    private const val Rate = 136
    private val RoundConstants = longArrayOf(
        0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(), 0x800000000000808AuL.toLong(), 0x8000000080008000uL.toLong(),
        0x000000000000808BuL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(),
        0x000000000000008AuL.toLong(), 0x0000000000000088uL.toLong(), 0x0000000080008009uL.toLong(), 0x000000008000000AuL.toLong(),
        0x000000008000808BuL.toLong(), 0x800000000000008BuL.toLong(), 0x8000000000008089uL.toLong(), 0x8000000000008003uL.toLong(),
        0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(), 0x000000000000800AuL.toLong(), 0x800000008000000AuL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008080uL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong(),
    )
    /** Rotation offsets, indexed x + 5y. */
    private val Rotations = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)

    fun hash(input: ByteArray): ByteArray {
        val state = LongArray(25)
        val blocks = input.size / Rate + 1
        val padded = input.copyOf(blocks * Rate)
        padded[input.size] = (padded[input.size].toInt() xor 0x01).toByte()
        padded[padded.lastIndex] = (padded[padded.lastIndex].toInt() xor 0x80).toByte()
        for (b in 0 until blocks) {
            for (i in 0 until Rate / 8) {
                var lane = 0L
                for (k in 7 downTo 0) lane = (lane shl 8) or (padded[b * Rate + i * 8 + k].toLong() and 0xff)
                state[i] = state[i] xor lane
            }
            permute(state)
        }
        return ByteArray(32) { i -> (state[i / 8] ushr (8 * (i % 8))).toByte() }
    }

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            for (x in 0 until 5) for (y in 0 until 5) b[y + 5 * ((2 * x + 3 * y) % 5)] = a[x + 5 * y].rotateLeft(Rotations[x + 5 * y])
            for (y in 0 until 5) for (x in 0 until 5) a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            a[0] = a[0] xor RoundConstants[round]
        }
    }
}
