package com.wrbug.polymarketbot.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface BuilderRelayerApi {
    
    /**
     * POST /submit
     * 
     * - POLY_BUILDER_API_KEY: API Key
     * - POLY_BUILDER_PASSPHRASE: Passphrase
     */
    @POST("/submit")
    suspend fun submitTransaction(
        @Body request: TransactionRequest
    ): Response<RelayerTransactionResponse>
    
    /**
     * GET /nonce?address={address}&type={type}
     */
    @GET("/nonce")
    suspend fun getNonce(
        @Query("address") address: String,
        @Query("type") type: String
    ): Response<NoncePayload>

    /**
     * GET /relay-payload?address={address}&type=PROXY
     */
    @GET("/relay-payload")
    suspend fun getRelayPayload(
        @Query("address") address: String,
        @Query("type") type: String
    ): Response<RelayPayload>
    
    /**
     * GET /transaction?id={transactionId}
     */
    @GET("/transaction")
    suspend fun getTransaction(
        @Query("id") transactionId: String
    ): Response<List<RelayerTransaction>>
    
    /**
     * GET /deployed?address={address}
     */
    @GET("/deployed")
    suspend fun getDeployed(
        @Query("address") address: String
    ): Response<GetDeployedResponse>
    
    data class TransactionRequest(
        @SerializedName("type")
        val type: String,
        
        @SerializedName("from")
        val from: String,
        
        @SerializedName("to")
        val to: String,
        
        @SerializedName("proxyWallet")
        val proxyWallet: String,
        
        @SerializedName("data")
        val data: String,
        
        @SerializedName("nonce")
        val nonce: String? = null,
        
        @SerializedName("signature")
        val signature: String,
        
        @SerializedName("signatureParams")
        val signatureParams: SignatureParams,
        
        @SerializedName("metadata")
        val metadata: String? = null
    )
    
    data class SignatureParams(
        @SerializedName("gasPrice")
        val gasPrice: String? = null,
        
        @SerializedName("operation")
        val operation: String? = null,
        
        @SerializedName("safeTxnGas")
        val safeTxnGas: String? = null,
        
        @SerializedName("baseGas")
        val baseGas: String? = null,
        
        @SerializedName("gasToken")
        val gasToken: String? = null,
        
        @SerializedName("refundReceiver")
        val refundReceiver: String? = null,
        
        @SerializedName("relayerFee")
        val relayerFee: String? = null,
        
        @SerializedName("gasLimit")
        val gasLimit: String? = null,
        
        @SerializedName("relayHub")
        val relayHub: String? = null,
        
        @SerializedName("relay")
        val relay: String? = null,

        @SerializedName("paymentToken")
        val paymentToken: String? = null,

        @SerializedName("payment")
        val payment: String? = null,

        @SerializedName("paymentReceiver")
        val paymentReceiver: String? = null
    )
    
    data class RelayerTransactionResponse(
        @SerializedName("transactionID")
        val transactionID: String,
        
        @SerializedName("state")
        val state: String,  // STATE_NEW, STATE_EXECUTED, STATE_MINED, STATE_CONFIRMED, STATE_FAILED, STATE_INVALID
        
        @SerializedName("transactionHash")
        val transactionHash: String?,
        
        @SerializedName("hash")
        val hash: String?
    )
    
    data class NoncePayload(
        @SerializedName("nonce")
        val nonce: String
    )

    data class RelayPayload(
        @SerializedName("address")
        val address: String,
        @SerializedName("nonce")
        val nonce: String
    )
    
    data class RelayerTransaction(
        @SerializedName("transactionID")
        val transactionID: String,
        
        @SerializedName("transactionHash")
        val transactionHash: String,
        
        @SerializedName("from")
        val from: String,
        
        @SerializedName("to")
        val to: String,
        
        @SerializedName("proxyAddress")
        val proxyAddress: String,
        
        @SerializedName("data")
        val data: String,
        
        @SerializedName("nonce")
        val nonce: String,
        
        @SerializedName("value")
        val value: String,
        
        @SerializedName("state")
        val state: String,
        
        @SerializedName("type")
        val type: String,
        
        @SerializedName("metadata")
        val metadata: String,
        
        @SerializedName("createdAt")
        val createdAt: String,
        
        @SerializedName("updatedAt")
        val updatedAt: String
    )
    
    data class GetDeployedResponse(
        @SerializedName("deployed")
        val deployed: Boolean
    )
}

