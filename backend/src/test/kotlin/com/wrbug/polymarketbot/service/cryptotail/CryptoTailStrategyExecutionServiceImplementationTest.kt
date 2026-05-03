package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CryptoTailStrategyExecutionServiceImplementationTest {

    private val sourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "service",
        "cryptotail",
        "CryptoTailStrategyExecutionService.kt"
    )

    @Test
    fun `period context cache should not retain decrypted credentials or authenticated clients`() {
        val source = Files.readString(sourcePath)
        val periodContextBlock = Regex(
            "private data class PeriodContext\\((.*?)\\)\\s",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(source)?.groupValues?.get(1)
            ?: error("PeriodContext declaration not found")

        assertFalse(
            periodContextBlock.contains("decryptedPrivateKey"),
            "PeriodContext should not cache decrypted private keys"
        )
        assertFalse(
            periodContextBlock.contains("apiSecretDecrypted"),
            "PeriodContext should not cache decrypted API secrets"
        )
        assertFalse(
            periodContextBlock.contains("apiPassphraseDecrypted"),
            "PeriodContext should not cache decrypted API passphrases"
        )
        assertFalse(
            periodContextBlock.contains("clobApi"),
            "PeriodContext should not keep authenticated CLOB clients alive across the whole period"
        )

        val ensurePeriodContextBlock = Regex(
            "private suspend fun ensurePeriodContext\\((.*?)\\n    \\}",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(source)?.value
            ?: error("ensurePeriodContext declaration not found")

        assertFalse(
            ensurePeriodContextBlock.contains("cryptoUtils.decrypt"),
            "ensurePeriodContext should not decrypt credentials during period preheat"
        )
        assertFalse(
            ensurePeriodContextBlock.contains("createClobApi"),
            "ensurePeriodContext should not create authenticated CLOB clients during period preheat"
        )
    }
}
