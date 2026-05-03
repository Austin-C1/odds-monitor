package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RetrofitFactoryImplementationTest {

    private val sourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "util",
        "RetrofitFactory.kt"
    )

    @Test
    fun `retrofit factory should not keep legacy placeholder urls`() {
        val source = Files.readString(sourcePath)
        val legacyRpcHost = "https://polyrpc." + "poly" + "hermes/"

        assertFalse(
            source.contains(legacyRpcHost),
            "RetrofitFactory should use a neutral RPC placeholder instead of the old legacy host"
        )

        assertFalse(
            source.contains("https://api.github.com"),
            "RetrofitFactory should not keep the retired GitHub client wiring"
        )
    }

    @Test
    fun `retrofit factory should close rpc validation responses`() {
        val source = Files.readString(sourcePath)

        assertFalse(
            source.contains("val response = testClient.newCall(request).execute()"),
            "RPC validation should not leave execute responses open"
        )

        assertTrue(
            source.contains("testClient.newCall(request).execute().use { response ->"),
            "RPC validation should close the HTTP response with use"
        )
    }
}
