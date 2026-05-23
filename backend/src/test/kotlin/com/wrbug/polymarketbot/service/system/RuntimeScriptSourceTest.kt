package com.wrbug.polymarketbot.service.system

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RuntimeScriptSourceTest {
    @Test
    fun `backend startup script forces utf8 java logging output`() {
        val source = Files.readString(Path.of("../start-odds-backend.ps1"))

        assertTrue(source.contains("-Dfile.encoding=UTF-8"))
        assertTrue(source.contains("-Dsun.stdout.encoding=UTF-8"))
        assertTrue(source.contains("-Dsun.stderr.encoding=UTF-8"))
    }
}
