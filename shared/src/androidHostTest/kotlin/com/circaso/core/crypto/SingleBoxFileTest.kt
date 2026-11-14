package com.circaso.core.crypto

import com.circaso.core.platform.MediaFiles
import com.circaso.testing.runSuspending
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The single AES-GCM box made and opened from a file a piece at a time (libsignal's incremental GCM, 2026-11-14)
 * against the one-shot every app version has (the JCA's `AES/GCM/NoPadding`): the same bytes both ways, so a moment
 * sealed by a new phone opens on an old one and the other way round.
 */
class SingleBoxFileTest {
    private val crypto = AndroidSignalCrypto()
    private val files = MemoryFiles()
    private val key = crypto.randomBytes(32)

    /** Sizes around the piece's edge: empty, one byte, just under, exactly, just over, and several pieces with a tail. */
    private val sizes = listOf(0, 1, SingleBoxFile.PieceBytes - 1, SingleBoxFile.PieceBytes, SingleBoxFile.PieceBytes + 1, SingleBoxFile.PieceBytes * 2 + 12_345)

    @Test fun aBoxSealedFromAFileIsTheOneShotsBoxAndOpensWithIt() = runSuspending {
        for (size in sizes) {
            val plain = Random(size).nextBytes(size)
            files.write("plain", plain)
            val sealed = assertNotNull(SingleBoxFile.seal(crypto, files, key, "plain", "sealed"), "size $size")
            val box = files.read("sealed")!!
            assertEquals(size + 28L, sealed.size)
            assertEquals(sealed.size, box.size.toLong())
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(box), sealed.digest, "the digest is of the ciphertext as uploaded")
            // What an older app does with it: the one-shot, in memory.
            assertContentEquals(plain, crypto.aesGcmOpen(key, box), "size $size")
        }
    }

    @Test fun aBoxSealedByTheOneShotOpensFromAFile() = runSuspending {
        for (size in sizes) {
            val plain = Random(size + 7).nextBytes(size)
            val box = crypto.aesGcmSeal(key, plain) // what an older app, or an iPhone, uploads
            files.write("sealed", box)
            assertEquals(true, SingleBoxFile.open(crypto, files, key, "sealed", "opened", crypto.sha256(box)), "size $size")
            assertContentEquals(plain, files.read("opened"), "size $size")
            assertNull(files.read("opened.part"))
            files.delete("opened")
        }
    }

    @Test fun aChangedBoxAWrongKeyOrAWrongDigestNeverLeavesAFile() = runSuspending {
        val plain = Random(1).nextBytes(SingleBoxFile.PieceBytes + 99)
        val box = crypto.aesGcmSeal(key, plain)
        val digest = crypto.sha256(box)

        // One bit of the ciphertext changed, and the digest with it (as if the body carried the attacker's digest): the tag catches it.
        val changed = box.copyOf().also { it[5_000] = (it[5_000].toInt() xor 1).toByte() }
        files.write("sealed", changed)
        assertEquals(false, SingleBoxFile.open(crypto, files, key, "sealed", "opened", crypto.sha256(changed)))
        // The right box under another digest: refused before anything is kept.
        files.write("sealed", box)
        assertEquals(false, SingleBoxFile.open(crypto, files, key, "sealed", "opened", ByteArray(32)))
        // The wrong key.
        assertEquals(false, SingleBoxFile.open(crypto, files, crypto.randomBytes(32), "sealed", "opened", digest))
        // Cut short (a download that stopped), and too short to be a box at all.
        files.write("sealed", box.copyOf(box.size - 3))
        assertEquals(false, SingleBoxFile.open(crypto, files, key, "sealed", "opened", null))
        files.write("sealed", ByteArray(20))
        assertEquals(false, SingleBoxFile.open(crypto, files, key, "sealed", "opened", null))
        assertNull(files.read("opened"))
        assertNull(files.read("opened.part"))

        files.write("sealed", box)
        assertEquals(true, SingleBoxFile.open(crypto, files, key, "sealed", "opened", digest))
        assertContentEquals(plain, files.read("opened"))
    }

    @Test fun twoSealsOfTheSameFileNeverShareANonce() = runSuspending {
        files.write("plain", ByteArray(64) { 7 })
        SingleBoxFile.seal(crypto, files, key, "plain", "a")
        SingleBoxFile.seal(crypto, files, key, "plain", "b")
        assertFalse(files.read("a")!!.copyOf(12).contentEquals(files.read("b")!!.copyOf(12)))
    }

    @Test fun aKeyThatIsNotAes256OrAMissingFileFallsBackToTheCaller() = runSuspending {
        files.write("plain", ByteArray(10))
        assertNull(SingleBoxFile.seal(crypto, files, ByteArray(16), "plain", "sealed"))
        assertNull(files.read("sealed"))
        assertNull(SingleBoxFile.seal(crypto, files, key, "nothing-here", "sealed"))
        assertTrue(crypto.gcmStream(key, ByteArray(12), seal = true) != null)
    }
}

private class MemoryFiles : MediaFiles {
    private val map = mutableMapOf<String, ByteArray>()
    override suspend fun read(name: String) = map[name]
    override suspend fun write(name: String, bytes: ByteArray) { map[name] = bytes }
    override suspend fun clear() = map.clear()
    override suspend fun delete(name: String) { map.remove(name) }
}
