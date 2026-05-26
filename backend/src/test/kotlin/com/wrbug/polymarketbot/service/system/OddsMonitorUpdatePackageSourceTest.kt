package com.wrbug.polymarketbot.service.system

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OddsMonitorUpdatePackageSourceTest {
    @Test
    fun `update package preserves old install data and carries migrations`() {
        val packageScript = readRootFile("build-odds-monitor-update-package.ps1")
        val launchScript = readRootFile("launch-odds-monitor.ps1")
        val startScript = readRootFile("start-odds-backend.ps1")

        assertTrue(
            packageScript.contains("Java runtime files are not allowed in update packages"),
            "update packages must stay separate from full install packages"
        )
        assertTrue(
            packageScript.contains("odds-monitor-backend-${'$'}version.jar"),
            "update package must include the backend jar so old databases receive Flyway migrations"
        )
        assertTrue(packageScript.contains("launch-odds-monitor.cmd"))
        assertTrue(packageScript.contains("start-odds-backend.ps1"))
        assertTrue(packageScript.contains("scripts\\serve-odds-frontend.ps1"))
        assertTrue(packageScript.contains("frontend') -Force"))
        assertTrue(
            packageScript.contains("'config', 'data', 'logs', 'backups', 'updates', 'accounts', 'telegram settings'"),
            "update package manifest must document old-user local data preservation"
        )

        assertTrue(
            launchScript.contains("\$localConfig = Join-Path \$rootDir 'config\\local.env.ps1'"),
            "launcher must keep old-version local environment overrides portable"
        )
        assertTrue(
            launchScript.contains("\$databaseVolumeName = 'odds-monitor-mysql-data'"),
            "launcher must reuse the old MySQL volume instead of creating a new empty database"
        )
        assertTrue(
            startScript.contains("\$env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_ENABLED"),
            "packaged local login must remain available after upgrading old installs"
        )
    }

    private fun readRootFile(relativePath: String): String {
        return Files.readString(Path.of("..", relativePath))
    }
}
