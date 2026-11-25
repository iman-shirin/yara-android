package com.yara.assistant

import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EncryptedChatArchiveTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun encryptedArchiveRoundTripsAndHidesPlaintext() {
        val lines = listOf(ChatLine("user", "سلام"), ChatLine("assistant", "پاسخ خصوصی"))
        val payload = ChatArchiveCodec.encode(lines)
        val encrypted = EncryptedChatCrypto.encrypt(payload, key)

        val secretBytes = "پاسخ خصوصی".toByteArray(Charsets.UTF_8)
        assertFalse(encrypted.asList().windowed(secretBytes.size).any { it == secretBytes.toList() })
        assertEquals(lines, ChatArchiveCodec.decode(EncryptedChatCrypto.decrypt(encrypted, key)))
    }

    @Test
    fun authenticationFailureRejectsModifiedCiphertext() {
        val encrypted = EncryptedChatCrypto.encrypt(ChatArchiveCodec.encode(listOf(ChatLine("user", "سلام"))), key)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertThrows(Exception::class.java) {
            EncryptedChatCrypto.decrypt(encrypted, key)
        }
    }

    @Test
    fun codecRejectsUnknownVersionAndTruncatedData() {
        val valid = ChatArchiveCodec.encode(listOf(ChatLine("user", "سلام")))
        val unknownVersion = valid.copyOf().also { it[7] = 2 }

        assertThrows(IllegalArgumentException::class.java) { ChatArchiveCodec.decode(unknownVersion) }
        assertThrows(Exception::class.java) { ChatArchiveCodec.decode(valid.copyOf(valid.size - 1)) }
    }

    @Test
    fun encryptedMultiConversationArchiveRoundTripsIdsTitlesAndMessages() {
        val conversations = listOf(
            ChatConversation("chat-a", "پرسش اول", 10L, listOf(ChatLine("user", "سلام"))),
            ChatConversation("chat-b", "پرسش دوم", 20L, listOf(ChatLine("assistant", "پاسخ خصوصی")))
        )
        val encrypted = EncryptedChatCrypto.encrypt(ChatArchiveCodec.encodeConversations(conversations), key)
        val decoded = ChatArchiveCodec.decodeConversations(EncryptedChatCrypto.decrypt(encrypted, key))

        assertEquals(conversations.reversed(), decoded)
    }

    @Test
    fun versionOneSingleChatArchiveUpgradesToOneConversation() {
        val lines = listOf(ChatLine("user", "تاریخچه قبلی"), ChatLine("assistant", "پاسخ"))
        val decoded = ChatArchiveCodec.decodeConversations(ChatArchiveCodec.encode(lines))

        assertEquals(1, decoded.size)
        assertEquals(lines, decoded.single().messages)
        assertNotEquals("", decoded.single().id)
    }

    @Test
    fun providerRetryPolicyFailsClosedForAuthBillingQuotaAndRateLimit() {
        listOf(401, 402, 403, 429).forEach { status ->
            assertFalse(YaraProviderRetryPolicy.shouldRetry(IllegalStateException("HTTP $status: rejected")))
        }
        assertTrue(YaraProviderRetryPolicy.shouldRetry(IllegalStateException("HTTP 408: timeout")))
        assertTrue(YaraProviderRetryPolicy.shouldRetry(IllegalStateException("HTTP 503: unavailable")))
        assertTrue(YaraProviderRetryPolicy.shouldRetry(IOException("network timeout")))
    }
}

