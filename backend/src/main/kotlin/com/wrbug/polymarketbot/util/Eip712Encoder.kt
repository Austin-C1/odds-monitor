package com.wrbug.polymarketbot.util

import org.bouncycastle.crypto.digests.KeccakDigest
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.charset.StandardCharsets

object Eip712Encoder {
    
    private fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return hash
    }
    
    private fun encodeString(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return keccak256(bytes)
    }
    
    private fun encodeAddress(address: String): ByteArray {
        val cleanAddress = address.removePrefix("0x").lowercase()
        val addressBytes = Numeric.hexStringToByteArray("0x$cleanAddress")
        return ByteArray(32).apply {
            System.arraycopy(addressBytes, 0, this, 12, addressBytes.size)
        }
    }

    private fun encodeBytes32(value: String): ByteArray {
        val cleanValue = value.removePrefix("0x")
        val valueBytes = Numeric.hexStringToByteArray("0x$cleanValue")
        require(valueBytes.size <= 32) { "bytes32 value exceeds 32 bytes" }
        return ByteArray(32).apply {
            System.arraycopy(valueBytes, 0, this, 32 - valueBytes.size, valueBytes.size)
        }
    }
    
    private fun encodeUint256(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val result = ByteArray(32)
        if (bytes.size <= 32) {
            System.arraycopy(bytes, 0, result, 32 - bytes.size, bytes.size)
        } else {
            System.arraycopy(bytes, bytes.size - 32, result, 0, 32)
        }
        return result
    }
    
    private fun encodeType(typeName: String, fields: List<Pair<String, String>>): ByteArray {
        val typeString = buildString {
            append(typeName)
            append("(")
            fields.forEachIndexed { index, (name, type) ->
                if (index > 0) append(",")
                append(type)
                append(" ")
                append(name)
            }
            append(")")
        }
        return keccak256(typeString.toByteArray(StandardCharsets.UTF_8))
    }
    
    fun encodeDomain(
        name: String,
        version: String,
        chainId: Long
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "version" to "string",
                "chainId" to "uint256"
            )
        )
        val nameHash = encodeString(name)
        val versionHash = encodeString(version)
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(versionHash, 0, encoded, 64, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 96, 32)
        
        return keccak256(encoded)
    }
    
    fun encodeMessage(
        address: String,
        timestamp: String,
        nonce: BigInteger,
        message: String
    ): ByteArray {
        val clobAuthTypeHash = encodeType(
            "ClobAuth",
            listOf(
                "address" to "address",
                "timestamp" to "string",
                "nonce" to "uint256",
                "message" to "string"
            )
        )
        val addressBytes = encodeAddress(address)
        val timestampHash = encodeString(timestamp)
        val nonceBytes = encodeUint256(nonce)
        val messageHash = encodeString(message)
        val encoded = ByteArray(32 + 32 + 32 + 32 + 32)
        System.arraycopy(clobAuthTypeHash, 0, encoded, 0, 32)
        System.arraycopy(addressBytes, 0, encoded, 32, 32)
        System.arraycopy(timestampHash, 0, encoded, 64, 32)
        System.arraycopy(nonceBytes, 0, encoded, 96, 32)
        System.arraycopy(messageHash, 0, encoded, 128, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * hash = keccak256("\x19\x01" || domainSeparator || messageHash)
     */
    fun hashStructuredData(
        domainSeparator: ByteArray,
        messageHash: ByteArray
    ): ByteArray {
        val prefix = byteArrayOf(0x19.toByte(), 0x01.toByte())
        val encoded = ByteArray(prefix.size + domainSeparator.size + messageHash.size)
        System.arraycopy(prefix, 0, encoded, 0, prefix.size)
        System.arraycopy(domainSeparator, 0, encoded, prefix.size, domainSeparator.size)
        System.arraycopy(messageHash, 0, encoded, prefix.size + domainSeparator.size, messageHash.size)
        
        return keccak256(encoded)
    }
    
    /**
     * Domain: { name: "Polymarket CTF Exchange", version: "2", chainId: chainId, verifyingContract: exchangeContract }
     */
    fun encodeExchangeDomain(
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "version" to "string",
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )
        
        val nameHash = encodeString("Polymarket CTF Exchange")
        val versionHash = encodeString("2")
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        
        val encoded = ByteArray(32 + 32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(versionHash, 0, encoded, 64, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 96, 32)
        System.arraycopy(contractBytes, 0, encoded, 128, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * V2 Order: { salt, maker, signer, tokenId, makerAmount, takerAmount, side, signatureType, timestamp, metadata, builder }
     */
    fun encodeExchangeOrder(
        salt: Long,
        maker: String,
        signer: String,
        tokenId: String,
        makerAmount: String,
        takerAmount: String,
        side: String,
        signatureType: Int,
        timestamp: String,
        metadata: String,
        builder: String
    ): ByteArray {
        val orderTypeHash = encodeType(
            "Order",
            listOf(
                "salt" to "uint256",
                "maker" to "address",
                "signer" to "address",
                "tokenId" to "uint256",
                "makerAmount" to "uint256",
                "takerAmount" to "uint256",
                "side" to "uint8",
                "signatureType" to "uint8",
                "timestamp" to "uint256",
                "metadata" to "bytes32",
                "builder" to "bytes32"
            )
        )
        val saltBytes = encodeUint256(BigInteger.valueOf(salt))
        val makerBytes = encodeAddress(maker)
        val signerBytes = encodeAddress(signer)
        val tokenIdBytes = encodeUint256(BigInteger(tokenId))
        val makerAmountBytes = encodeUint256(BigInteger(makerAmount))
        val takerAmountBytes = encodeUint256(BigInteger(takerAmount))
        val timestampBytes = encodeUint256(BigInteger(timestamp))
        val metadataBytes = encodeBytes32(metadata)
        val builderBytes = encodeBytes32(builder)
        val sideValue = when (side.uppercase()) {
            "BUY" -> 0
            "SELL" -> 1
            else -> throw IllegalArgumentException("side 必须是 BUY 或 SELL")
        }
        val sideBytes = encodeUint256(BigInteger.valueOf(sideValue.toLong()))
        val signatureTypeBytes = encodeUint256(BigInteger.valueOf(signatureType.toLong()))
        val encoded = ByteArray(32 * 12)
        var offset = 0
        System.arraycopy(orderTypeHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(saltBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(makerBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(signerBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(tokenIdBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(makerAmountBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(takerAmountBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(sideBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(signatureTypeBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(timestampBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(metadataBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(builderBytes, 0, encoded, offset, 32)

        return keccak256(encoded)
    }
    
    /**
     * Domain: { chainId: uint256, verifyingContract: address }
     */
    fun encodeSafeDomain(
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )
        
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        
        val encoded = ByteArray(32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 32, 32)
        System.arraycopy(contractBytes, 0, encoded, 64, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * SafeTx: { to, value, data, operation, safeTxGas, baseGas, gasPrice, gasToken, refundReceiver, nonce }
     */
    fun encodeSafeTx(
        to: String,
        value: BigInteger,
        data: String,
        operation: Int, // 0 = CALL, 1 = DELEGATECALL
        safeTxGas: BigInteger,
        baseGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: String,
        refundReceiver: String,
        nonce: BigInteger
    ): ByteArray {
        val safeTxTypeHash = encodeType(
            "SafeTx",
            listOf(
                "to" to "address",
                "value" to "uint256",
                "data" to "bytes",
                "operation" to "uint8",
                "safeTxGas" to "uint256",
                "baseGas" to "uint256",
                "gasPrice" to "uint256",
                "gasToken" to "address",
                "refundReceiver" to "address",
                "nonce" to "uint256"
            )
        )
        val toBytes = encodeAddress(to)
        val valueBytes = encodeUint256(value)
        val dataBytes = if (data.isBlank() || data == "0x") {
            ByteArray(32)
        } else {
            val cleanData = data.removePrefix("0x")
            val dataByteArray = Numeric.hexStringToByteArray("0x$cleanData")
            keccak256(dataByteArray)
        }
        val operationBytes = encodeUint256(BigInteger.valueOf(operation.toLong()))
        val safeTxGasBytes = encodeUint256(safeTxGas)
        val baseGasBytes = encodeUint256(baseGas)
        val gasPriceBytes = encodeUint256(gasPrice)
        val gasTokenBytes = encodeAddress(gasToken)
        val refundReceiverBytes = encodeAddress(refundReceiver)
        val nonceBytes = encodeUint256(nonce)
        val encoded = ByteArray(32 * 11)
        var offset = 0
        System.arraycopy(safeTxTypeHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(toBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(valueBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(dataBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(operationBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(safeTxGasBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(baseGasBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(gasPriceBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(gasTokenBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(refundReceiverBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(nonceBytes, 0, encoded, offset, 32)
        
        return keccak256(encoded)
    }

    /**
     * Domain: EIP712Domain(string name, uint256 chainId, address verifyingContract)
     */
    fun encodeSafeCreateDomain(
        name: String,
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )
        val nameHash = encodeString(name)
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 64, 32)
        System.arraycopy(contractBytes, 0, encoded, 96, 32)
        return keccak256(encoded)
    }

    /**
     * CreateProxy(address paymentToken, uint256 payment, address paymentReceiver)
     */
    fun encodeCreateProxyMessage(
        paymentToken: String,
        payment: BigInteger,
        paymentReceiver: String
    ): ByteArray {
        val typeHash = encodeType(
            "CreateProxy",
            listOf(
                "paymentToken" to "address",
                "payment" to "uint256",
                "paymentReceiver" to "address"
            )
        )
        val tokenBytes = encodeAddress(paymentToken)
        val paymentBytes = encodeUint256(payment)
        val receiverBytes = encodeAddress(paymentReceiver)
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(typeHash, 0, encoded, 0, 32)
        System.arraycopy(tokenBytes, 0, encoded, 32, 32)
        System.arraycopy(paymentBytes, 0, encoded, 64, 32)
        System.arraycopy(receiverBytes, 0, encoded, 96, 32)
        return keccak256(encoded)
    }
}

