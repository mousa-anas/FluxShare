package app.fluxshare.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncrementalSha256Test {
    private val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `hashes an empty stream`() {
        assertEquals(emptySha256, IncrementalSha256().digest().toHex())
    }

    @Test
    fun `hashes the abc vector`() {
        assertEquals(abcSha256, "abc".toByteArray().sha256Hex())
    }

    @Test
    fun `incremental updates match a single pass`() {
        val data = ByteArray(200_000) { index -> ((index * 31) and 0xFF).toByte() }

        val onePass = IncrementalSha256().apply { update(data) }.digest()

        val incremental = IncrementalSha256()
        var offset = 0
        var chunk = 1
        while (offset < data.size) {
            val length = minOf(chunk, data.size - offset)
            incremental.update(data, offset, length)
            offset += length
            chunk = minOf(chunk * 2, 8_192)
        }

        assertEquals(data.size.toLong(), incremental.bytesHashed)
        assertContentEquals(onePass, incremental.digest())
    }

    @Test
    fun `hex output is lowercase and fixed width`() {
        val hex = ByteArray(32) { 0xAB.toByte() }.toHex()
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `rejects updates after finalization`() {
        val digest = IncrementalSha256()
        digest.update(byteArrayOf(1))
        digest.digest()
        assertFailsWith<IllegalArgumentException> { digest.update(byteArrayOf(2)) }
        assertFailsWith<IllegalArgumentException> { digest.digest() }
    }

    @Test
    fun `rejects out of bounds ranges`() {
        val digest = IncrementalSha256()
        assertFailsWith<IllegalArgumentException> { digest.update(ByteArray(4), offset = 3, length = 4) }
        assertFailsWith<IllegalArgumentException> { digest.update(ByteArray(4), offset = -1, length = 2) }
    }
}
