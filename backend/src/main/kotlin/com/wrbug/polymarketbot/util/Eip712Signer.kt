package com.wrbug.polymarketbot.util

import org.slf4j.LoggerFactory
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.math.BigInteger

object Eip712Signer {
    
    private val logger = LoggerFactory.getLogger(Eip712Signer::class.java)
    
    private const val DOMAIN_NAME = "ClobAuthDomain"
    private const val DOMAIN_VERSION = "1"
    private const val MESSAGE_TO_SIGN = "This message attests that I control the given wallet"
    
    fun buildClobEip712Signature(
        privateKey: String,
        chainId: Long,
        timestamp: Long,
        nonce: Long = 0
    ): String {
        try {
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
            val address = credentials.address
            val domainSeparator = Eip712Encoder.encodeDomain(
                name = DOMAIN_NAME,
                version = DOMAIN_VERSION,
                chainId = chainId
            )
            val messageHash = Eip712Encoder.encodeMessage(
                address = address,
                timestamp = timestamp.toString(),
                nonce = BigInteger.valueOf(nonce),
                message = MESSAGE_TO_SIGN
            )
            val structuredHash = Eip712Encoder.hashStructuredData(domainSeparator, messageHash)
            val ecKeyPair = ECKeyPair.create(privateKeyBigInt)
            val signature = Sign.signMessage(structuredHash, ecKeyPair, false)
            val rHex = Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
            val sHex = Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
            val vBytes = signature.v as ByteArray
            val vInt = if (vBytes.isNotEmpty()) {
                vBytes[0].toInt() and 0xff
            } else {
                0
            }
            val vHex = String.format("%02x", vInt)
            
            return "0x$rHex$sHex$vHex"
        } catch (e: Exception) {
            logger.error("EIP-712 签名失败", e)
            throw RuntimeException("EIP-712 签名失败: ${e.message}", e)
        }
    }
}

