package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.Account
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AccountRepository : JpaRepository<Account, Long> {

    fun findByWalletAddress(walletAddress: String): Account?

    fun findByIsDefaultTrue(): Account?

    fun findAllByOrderByCreatedAtAsc(): List<Account>

    fun countByBuilderApiKeyIsNotNullAndBuilderSecretIsNotNullAndBuilderPassphraseIsNotNull(): Long

    fun findFirstByBuilderApiKeyIsNotNullAndBuilderSecretIsNotNullAndBuilderPassphraseIsNotNullOrderByCreatedAtAsc(): Account?

    fun existsByWalletAddress(walletAddress: String): Boolean

    fun existsByProxyAddress(proxyAddress: String): Boolean
}
