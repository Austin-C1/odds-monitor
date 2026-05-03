package com.wrbug.polymarketbot.event

import org.springframework.context.ApplicationEvent

class AccountStateChangedEvent(
    source: Any,
    val accountId: Long,
    val walletAddress: String?
) : ApplicationEvent(source)
