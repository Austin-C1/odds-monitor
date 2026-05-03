package com.wrbug.polymarketbot.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LoggingClasspathTest {

    @Test
    fun `jul bridge is not present on runtime classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("org.slf4j.bridge.SLF4JBridgeHandler")
        }
    }
}
