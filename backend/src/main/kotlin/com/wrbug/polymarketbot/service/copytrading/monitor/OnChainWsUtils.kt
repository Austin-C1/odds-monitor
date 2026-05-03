package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken
import com.wrbug.polymarketbot.api.*
import com.wrbug.polymarketbot.service.system.RpcNodeService
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.math.BigInteger

object OnChainWsUtils {
    
    private val logger = LoggerFactory.getLogger(OnChainWsUtils::class.java)
    private val gson: Gson = GsonBuilder()
        .setLenient()
        .create()
    
    private fun parseStringArray(jsonString: String?): List<String> {
        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }
        
        return try {
            val listType = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(jsonString, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    const val USDC_CONTRACT = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"
    const val ERC1155_CONTRACT = "0x4d97dcd97ec945f40cf65f87097ace5ea0476045"
    const val ERC20_TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    const val ERC1155_TRANSFER_SINGLE_TOPIC = "0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62"
    const val ERC1155_TRANSFER_BATCH_TOPIC = "0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb"
    
    data class Erc20Transfer(
        val from: String,
        val to: String,
        val value: BigInteger
    )
    
    data class Erc1155Transfer(
        val from: String,
        val to: String,
        val tokenId: BigInteger,
        val value: BigInteger
    )
    
    data class MarketInfo(
        val conditionId: String,
        val outcomeIndex: Int?,
        val outcome: String?
    )
    
    fun parseReceiptTransfers(logs: JsonArray): Pair<List<Erc20Transfer>, List<Erc1155Transfer>> {
        val erc20 = mutableListOf<Erc20Transfer>()
        val erc1155 = mutableListOf<Erc1155Transfer>()
        
        for (logElement in logs) {
            val log = logElement.asJsonObject
            val address = log.get("address")?.asString?.lowercase() ?: continue
            val topicsArray = log.getAsJsonArray("topics") ?: continue
            val topics = topicsArray.mapNotNull { it.asString }
            if (topics.isEmpty()) continue
            
            val t0 = topics[0].lowercase()
            val data = log.get("data")?.asString ?: "0x"
            
            // USDC ERC20 Transfer
            if (address == USDC_CONTRACT.lowercase() && t0 == ERC20_TRANSFER_TOPIC && topics.size >= 3) {
                val from = topicToAddress(topics[1])
                val to = topicToAddress(topics[2])
                val value = hexToBigInt(data)
                erc20.add(Erc20Transfer(from, to, value))
                continue
            }
            
            // ERC1155 TransferSingle
            if (t0 == ERC1155_TRANSFER_SINGLE_TOPIC && topics.size >= 4) {
                val from = topicToAddress(topics[2])
                val to = topicToAddress(topics[3])
                val bytes = bytesFromHex(data)
                if (bytes.size >= 64) {
                    val tokenId = sliceBigInt32(bytes, 0)
                    val value = sliceBigInt32(bytes, 32)
                    erc1155.add(Erc1155Transfer(from, to, tokenId, value))
                }
                continue
            }
            
            // ERC1155 TransferBatch
            if (t0 == ERC1155_TRANSFER_BATCH_TOPIC && topics.size >= 4) {
                val from = topicToAddress(topics[2])
                val to = topicToAddress(topics[3])
                val bytes = bytesFromHex(data)
                if (bytes.size < 64) continue
                
                val offIds = sliceBigInt32(bytes, 0).toInt()
                val offVals = sliceBigInt32(bytes, 32).toInt()
                if (offIds + 32 > bytes.size || offVals + 32 > bytes.size) continue
                
                val nIds = sliceBigInt32(bytes, offIds).toInt()
                val nVals = sliceBigInt32(bytes, offVals).toInt()
                if (nIds != nVals) continue
                
                val idsStart = offIds + 32
                val valsStart = offVals + 32
                for (i in 0 until nIds) {
                    val ib = idsStart + i * 32
                    val vb = valsStart + i * 32
                    if (ib + 32 > bytes.size || vb + 32 > bytes.size) break
                    val tokenId = sliceBigInt32(bytes, ib)
                    val value = sliceBigInt32(bytes, vb)
                    erc1155.add(Erc1155Transfer(from, to, tokenId, value))
                }
            }
        }
        
        return Pair(erc20, erc1155)
    }
    
    suspend fun parseTradeFromTransfers(
        txHash: String,
        timestamp: Long?,
        walletAddress: String,
        erc20Transfers: List<Erc20Transfer>,
        erc1155Transfers: List<Erc1155Transfer>,
        retrofitFactory: RetrofitFactory
    ): TradeResponse? {
        val wallet = walletAddress.lowercase()
        val usdcOut = erc20Transfers.filter { it.from.lowercase() == wallet }
            .fold(BigInteger.ZERO) { acc, t -> acc + t.value }
        val usdcIn = erc20Transfers.filter { it.to.lowercase() == wallet }
            .fold(BigInteger.ZERO) { acc, t -> acc + t.value }
        val inById = mutableMapOf<BigInteger, BigInteger>()
        val outById = mutableMapOf<BigInteger, BigInteger>()
        for (t in erc1155Transfers) {
            if (t.to.lowercase() == wallet) {
                inById[t.tokenId] = (inById[t.tokenId] ?: BigInteger.ZERO) + t.value
            }
            if (t.from.lowercase() == wallet) {
                outById[t.tokenId] = (outById[t.tokenId] ?: BigInteger.ZERO) + t.value
            }
        }
        fun best(map: Map<BigInteger, BigInteger>): Pair<BigInteger?, BigInteger> =
            map.entries.maxByOrNull { it.value }?.let { it.key to it.value } ?: (null to BigInteger.ZERO)
        
        val (bestInId, bestInVal) = best(inById)
        val (bestOutId, bestOutVal) = best(outById)
        var side: String? = null
        var asset: BigInteger? = null
        var sizeRaw = BigInteger.ZERO
        var usdcRaw = BigInteger.ZERO
        
        if (bestInId != null && bestInVal > BigInteger.ZERO && usdcOut > BigInteger.ZERO) {
            side = "BUY"
            asset = bestInId
            sizeRaw = bestInVal
            usdcRaw = usdcOut
        } else if (bestOutId != null && bestOutVal > BigInteger.ZERO && usdcIn > BigInteger.ZERO) {
            side = "SELL"
            asset = bestOutId
            sizeRaw = bestOutVal
            usdcRaw = usdcIn
        } else {
            logger.debug("无法判断交易方向: txHash=$txHash, bestInId=$bestInId, bestInVal=$bestInVal, bestOutId=$bestOutId, bestOutVal=$bestOutVal, usdcOut=$usdcOut, usdcIn=$usdcIn")
            return null
        }
        val usdcSize = usdcRaw.toBigDecimal().divide(BigInteger("1000000").toBigDecimal(), 8, java.math.RoundingMode.DOWN)
        val size = sizeRaw.toBigDecimal().divide(BigInteger("1000000").toBigDecimal(), 8, java.math.RoundingMode.DOWN)
        val price = if (size.signum() > 0) {
            usdcSize.divide(size, 8, java.math.RoundingMode.DOWN)
        } else {
            return null
        }
        val marketInfo = fetchMarketByTokenId(asset.toString(), retrofitFactory)
        return TradeResponse(
            id = txHash,
            market = marketInfo?.conditionId ?: "",
            side = side,
            price = price.toPlainString(),
            size = size.toPlainString(),
            timestamp = (timestamp ?: System.currentTimeMillis() / 1000).toString(),
            user = walletAddress,
            outcomeIndex = marketInfo?.outcomeIndex,
            outcome = marketInfo?.outcome,
            tokenId = asset.toString()
        )
    }
    
    suspend fun fetchMarketByTokenId(tokenId: String, retrofitFactory: RetrofitFactory): MarketInfo? {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val marketsResponse = gammaApi.listMarkets(
                conditionIds = null,
                clobTokenIds = listOf(tokenId),
                includeTag = null
            )
            
            if (!marketsResponse.isSuccessful || marketsResponse.body() == null) {
                return null
            }
            
            val markets = marketsResponse.body()!!
            val market = markets.firstOrNull()
            
            if (market == null) {
                return null
            }
            val clobTokenIdsRaw = market.clobTokenIds ?: market.clob_token_ids
            val clobTokenIds = when {
                clobTokenIdsRaw == null -> null
                else -> {
                    parseStringArray(clobTokenIdsRaw)
                }
            }
            val outcomes = parseStringArray(market.outcomes)
            val outcomeIndex = clobTokenIds?.indexOfFirst { token ->
                token.equals(tokenId, ignoreCase = true)
            }?.takeIf { it >= 0 }
            val outcome = if (outcomeIndex != null && outcomes.isNotEmpty() && outcomeIndex < outcomes.size) {
                outcomes[outcomeIndex]
            } else {
                null
            }
            
            val conditionId = market.conditionId ?: return null
            
            MarketInfo(
                conditionId = conditionId,
                outcomeIndex = outcomeIndex,
                outcome = outcome
            )
        } catch (e: Exception) {
            logger.warn("查询市场信息失败: tokenId=$tokenId, error=${e.message}")
            null
        }
    }
    
    suspend fun getBlockTimestamp(blockNumber: String, rpcApi: EthereumRpcApi): Long? {
        return try {
            val blockRequest = JsonRpcRequest(
                method = "eth_getBlockByNumber",
                params = listOf(blockNumber, false)
            )
            
            val blockResponse = rpcApi.call(blockRequest)
            if (blockResponse.isSuccessful && blockResponse.body() != null) {
                val blockRpcResponse = blockResponse.body()!!
                if (blockRpcResponse.error == null && blockRpcResponse.result != null) {
                    val blockJson = blockRpcResponse.result.asJsonObject
                    val timestampHex = blockJson.get("timestamp")?.asString
                    if (timestampHex != null) {
                        BigInteger(timestampHex.removePrefix("0x"), 16).toLong() * 1000
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("获取区块时间戳失败: blockNumber=$blockNumber, error=${e.message}")
            null
        }
    }
    
    fun addressToTopic32(address: String): String {
        val clean = address.removePrefix("0x").lowercase()
        return "0x" + clean.padStart(64, '0')
    }
    
    fun topicToAddress(topic: String): String {
        val clean = topic.removePrefix("0x").lowercase()
        return "0x" + clean.takeLast(40)
    }
    
    fun hexToBigInt(hex: String): BigInteger {
        val clean = hex.removePrefix("0x")
        return if (clean.isBlank()) BigInteger.ZERO else BigInteger(clean, 16)
    }
    
    fun bytesFromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
    
    fun sliceBigInt32(bytes: ByteArray, offset: Int): BigInteger {
        if (offset + 32 > bytes.size) return BigInteger.ZERO
        val slice = bytes.sliceArray(offset until offset + 32)
        return BigInteger(1, slice)
    }
}

