package com.miruronative.data.remote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AllAnimeProviderTest {
    @Test
    fun decodesClockUrlsAndSourceRows() {
        assertEquals("/clock.json", AllAnimeCodec.decodeSourceUrl("--175b54575b53"))

        val root = Json.parseToJsonElement(
            """{"episode":{"sourceUrls":[{"sourceName":"Yt-mp4","sourceUrl":"https://cdn.test/video.mp4","type":"player","resolutionStr":"1080p","priority":10}]}}""",
        )
        val source = AllAnimeCodec.parseSources(root).single()

        assertEquals("Yt-mp4", source.name)
        assertEquals("https://cdn.test/video.mp4", source.url)
        assertEquals("1080p", source.quality)
        assertEquals(10, source.priority)
    }

    @Test
    fun decryptsAllAnimeAesCtrEnvelope() {
        val plaintext = """{"sourceUrls":[{"sourceName":"S","sourceUrl":"https://cdn.test/master.m3u8","priority":2}]}"""
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val counter = iv + byteArrayOf(0, 0, 0, 2)
        val key = MessageDigest.getInstance("SHA-256").digest("Xot36i3lK3:v1".toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val envelope = byteArrayOf(0) + iv + ciphertext + ByteArray(16) { 7 }
        val encoded = Base64.getEncoder().encodeToString(envelope)

        val source = AllAnimeCodec.parseSources(AllAnimeCodec.decrypt(encoded)).single()

        assertEquals("https://cdn.test/master.m3u8", source.url)
        assertEquals(2, source.priority)
    }
}
