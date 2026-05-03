package com.wrbug.polymarketbot.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class CryptoUtils {
    
    @Value("\${encryption.key}")
    private lateinit var encryptionKey: String
    
    private val ALGORITHM = "AES"
    private val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    
    private fun getSecretKey(): SecretKeySpec {
        val keyBytes = if (encryptionKey.length >= 64 && encryptionKey.matches(Regex("^[0-9a-fA-F]+$"))) {
            encryptionKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            encryptionKey.toByteArray(StandardCharsets.UTF_8)
        }
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(keyBytes)
        val hash = messageDigest.digest()
        
        return SecretKeySpec(hash, ALGORITHM)
    }
    
    fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            throw RuntimeException("加密失败: ${e.message}", e)
        }
    }
    
    fun decrypt(encryptedText: String): String {
        return try {
            val combined = Base64.getDecoder().decode(encryptedText)
            val iv = ByteArray(16)
            System.arraycopy(combined, 0, iv, 0, 16)
            val encryptedBytes = ByteArray(combined.size - 16)
            System.arraycopy(combined, 16, encryptedBytes, 0, encryptedBytes.size)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw RuntimeException("解密失败: ${e.message}", e)
        }
    }
    
    fun isEncrypted(text: String): Boolean {
        return try {
            val decoded = Base64.getDecoder().decode(text)
            decoded.size % 16 == 0 && decoded.size >= 16
        } catch (e: Exception) {
            false
        }
    }
}

