package com.wrbug.polymarketbot.enums

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ErrorCodeSourceTest {
    @Test
    fun `error codes should not keep deprecated compatibility helpers`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/wrbug/polymarketbot/enums/ErrorCode.kt"))

        assertFalse(source.contains("@Deprecated"))
        assertFalse(source.contains("fun getMessage("))
    }
}
