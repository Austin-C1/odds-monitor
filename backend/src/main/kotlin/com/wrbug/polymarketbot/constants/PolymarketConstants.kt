package com.wrbug.polymarketbot.constants

object PolymarketConstants {
    @JvmField
    val CLOB_BASE_URL: String = System.getenv("POLYMARKET_CLOB_BASE_URL")
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
        ?: "https://clob.polymarket.com"
    
    /**
     * Polymarket RTDS WebSocket URL
     */
    const val RTDS_WS_URL = "wss://ws-subscriptions-clob.polymarket.com"
    
    /**
     * Polymarket User Channel WebSocket URL
     */
    const val USER_WS_URL = "wss://ws-live-data.polymarket.com"
    
    /**
     * Polymarket Activity WebSocket URL
     */
    const val ACTIVITY_WS_URL = "wss://ws-live-data.polymarket.com"
    
    const val DATA_API_BASE_URL = "https://data-api.polymarket.com"
    
    const val GAMMA_BASE_URL = "https://gamma-api.polymarket.com"
    
    /**
     * Builder Relayer API URL
     */
    const val BUILDER_RELAYER_URL = "https://relayer-v2.polymarket.com/"

    const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    const val ZERO_BYTES32 = "0x0000000000000000000000000000000000000000000000000000000000000000"

    const val USDC_CONTRACT_ADDRESS = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"
    const val PUSD_CONTRACT_ADDRESS = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB"
    const val CTF_CONTRACT_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"
    const val CTF_EXCHANGE_V2_ADDRESS = "0xE111180000d2663C0091e4f400237545B87B996B"
    const val NEG_RISK_CTF_EXCHANGE_V2_ADDRESS = "0xe2222d279d744050d28e00520010520000310F59"
    const val NEG_RISK_ADAPTER_ADDRESS = "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296"
    const val NEG_RISK_WRAPPED_COLLATERAL_ADDRESS = "0x3A3BD7bb9528E159577F7C2e685CC81A765002E2"

    const val SAFE_PROXY_FACTORY_ADDRESS = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"

    const val SAFE_FACTORY_EIP712_NAME = "Polymarket Contract Proxy Factory"
}

