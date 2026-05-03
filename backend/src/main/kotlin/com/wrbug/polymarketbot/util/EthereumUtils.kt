package com.wrbug.polymarketbot.util

import org.bouncycastle.crypto.digests.KeccakDigest
import java.math.BigInteger

object EthereumUtils {
    private val COLLATERAL_TOKEN_ADDRESS = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB" // pUSD, displayed as USDC
    private val CONDITIONAL_TOKENS_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045" // ConditionalTokens
    
    fun getFunctionSelector(functionSignature: String): String {
        val hash = keccak256Hex(functionSignature.toByteArray())
        return "0x" + hash.substring(0, 8)
    }
    
    fun encodeAddress(address: String): String {
        val cleanAddress = address.removePrefix("0x").lowercase()
        return cleanAddress.padStart(64, '0')
    }
    
    fun encodeUint256(value: BigInteger): String {
        return value.toString(16).padStart(64, '0')
    }
    
    fun encodeBytes32(value: String): String {
        val cleanValue = value.removePrefix("0x")
        if (cleanValue.length != 64) {
            throw IllegalArgumentException("bytes32 值必须是64个十六进制字符")
        }
        return cleanValue.lowercase()
    }
    
    fun decodeAddress(hexResult: String): String {
        val cleanHex = hexResult.removePrefix("0x")
        val addressHex = cleanHex.takeLast(40)
        return "0x$addressHex"
    }
    
    fun decodeUint256(hexResult: String): BigInteger {
        val cleanHex = hexResult.removePrefix("0x")
        return BigInteger(cleanHex, 16)
    }
    
    fun decodeUint256Array(hexResult: String, offset: Int = 0): List<BigInteger> {
        val cleanHex = hexResult.removePrefix("0x")
        if (cleanHex.length < (offset + 1) * 64) {
            return emptyList()
        }
        val startPos = offset * 64
        val lengthHex = cleanHex.substring(startPos, startPos + 64)
        val length = BigInteger(lengthHex, 16).toInt()
        
        if (length <= 0 || length > 100) {
            return emptyList()
        }
        
        val result = mutableListOf<BigInteger>()
        for (i in 0 until length) {
            val elementStart = startPos + 64 + (i * 64)
            if (elementStart + 64 > cleanHex.length) {
                break
            }
            val elementHex = cleanHex.substring(elementStart, elementStart + 64)
            result.add(BigInteger(elementHex, 16))
        }
        
        return result
    }
    
    /**
     * @return Pair<payoutDenominator, payouts>
     */
    fun decodeConditionResult(hexResult: String): Pair<BigInteger, List<BigInteger>> {
        val cleanHex = hexResult.removePrefix("0x")
        val payoutDenominatorHex = cleanHex.substring(0, 64)
        val payoutDenominator = BigInteger(payoutDenominatorHex, 16)
        val offsetHex = cleanHex.substring(64, 128)
        val offset = BigInteger(offsetHex, 16).toInt() / 32
        val payouts = decodeUint256Array(hexResult, offset)
        
        return Pair(payoutDenominator, payouts)
    }
    
    fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("0x")
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        return "0x" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return hash
    }

    fun keccak256Hex(data: ByteArray): String {
        return keccak256(data).joinToString("") { "%02x".format(it) }
    }
}

