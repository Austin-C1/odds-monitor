package com.wrbug.polymarketbot.util

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtUtils {
    
    @Value("\${jwt.secret}")
    private lateinit var secret: String
    
    @Value("\${jwt.expiration}")
    private var expiration: Long = 604800000
    
    @Value("\${jwt.refresh-threshold}")
    private var refreshThreshold: Long = 86400000
    
    private fun getSigningKey(): SecretKey {
        val keyBytes = try {
            if (secret.length >= 64 && secret.matches(Regex("^[0-9a-fA-F]+$"))) {
                secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                secret.toByteArray()
            }
        } catch (e: Exception) {
            secret.toByteArray()
        }
        val finalKeyBytes = if (keyBytes.size < 32) {
            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
            messageDigest.update(keyBytes)
            messageDigest.digest()
        } else if (keyBytes.size > 64) {
            keyBytes.sliceArray(0 until 64)
        } else {
            keyBytes
        }
        
        return Keys.hmacShaKeyFor(finalKeyBytes)
    }
    
    fun generateToken(username: String, tokenVersion: Long = 0): String {
        val now = Date()
        val expiryDate = Date(now.time + expiration)
        
        return Jwts.builder()
            .subject(username)
            .claim("tokenVersion", tokenVersion)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(getSigningKey())
            .compact()
    }
    
    private fun getClaimsFromToken(token: String): Claims? {
        return try {
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: Exception) {
            null
        }
    }
    
    fun validateToken(token: String): Boolean {
        return try {
            val claims = getClaimsFromToken(token)
            claims != null && !isTokenExpired(token)
        } catch (e: Exception) {
            false
        }
    }
    
    fun getUsernameFromToken(token: String): String? {
        return try {
            val claims = getClaimsFromToken(token)
            claims?.subject
        } catch (e: Exception) {
            null
        }
    }
    
    fun getTokenVersionFromToken(token: String): Long? {
        return try {
            val claims = getClaimsFromToken(token)
            val version = claims?.get("tokenVersion")
            when (version) {
                is Number -> version.toLong()
                is String -> version.toLongOrNull()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    fun getIssuedAtFromToken(token: String): Long? {
        return try {
            val claims = getClaimsFromToken(token)
            claims?.issuedAt?.time
        } catch (e: Exception) {
            null
        }
    }
    
    fun isTokenExpired(token: String): Boolean {
        return try {
            val claims = getClaimsFromToken(token)
            val expiration = claims?.expiration ?: return true
            expiration.before(Date())
        } catch (e: Exception) {
            true
        }
    }
    
    fun isTokenExpiring(token: String): Boolean {
        return try {
            if (isTokenExpired(token)) {
                return false
            }
            val issuedAt = getIssuedAtFromToken(token) ?: return false
            val now = System.currentTimeMillis()
            val timeSinceIssued = now - issuedAt
            timeSinceIssued >= refreshThreshold
        } catch (e: Exception) {
            false
        }
    }
}

