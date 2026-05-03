package com.wrbug.polymarketbot.api

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface EthereumRpcApi {
    
    @POST("/")
    suspend fun call(@Body request: JsonRpcRequest): Response<JsonRpcResponse>
}

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: List<Any>,
    val id: Int = 1
)

data class JsonRpcResponse(
    val jsonrpc: String? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val id: Int? = null
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

