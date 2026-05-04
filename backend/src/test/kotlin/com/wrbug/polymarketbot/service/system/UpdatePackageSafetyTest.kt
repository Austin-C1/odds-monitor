package com.wrbug.polymarketbot.service.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UpdatePackageSafetyTest {

    @Test
    fun `program files are allowed to be updated`() {
        assertTrue(UpdatePackageSafety.isAllowedProgramPath("backend/build/libs/odds-monitor-backend-3.0.13.jar"))
        assertTrue(UpdatePackageSafety.isAllowedProgramPath("frontend/dist/index.html"))
        assertTrue(UpdatePackageSafety.isAllowedProgramPath("frontend/dist/assets/index.js"))
        assertTrue(UpdatePackageSafety.isAllowedProgramPath("scripts/serve-odds-frontend.ps1"))
        assertTrue(UpdatePackageSafety.isAllowedProgramPath("launch-odds-monitor.ps1"))
        assertTrue(UpdatePackageSafety.isAllowedProgramPath("launch-odds-monitor.cmd"))
        assertTrue(UpdatePackageSafety.isAllowedProgramPath("start-odds-backend.ps1"))
        assertFalse(UpdatePackageSafety.isAllowedProgramPath(".tools/jdk-17.0.18+8/bin/java.exe"))
    }

    @Test
    fun `user data and config paths are never overwritten`() {
        assertFalse(UpdatePackageSafety.isAllowedProgramPath(".env"))
        assertFalse(UpdatePackageSafety.isAllowedProgramPath("config/local.json"))
        assertFalse(UpdatePackageSafety.isAllowedProgramPath("data/mysql"))
        assertFalse(UpdatePackageSafety.isAllowedProgramPath("logs/backend.log"))
        assertFalse(UpdatePackageSafety.isAllowedProgramPath("backups/update.zip"))
        assertFalse(UpdatePackageSafety.isAllowedProgramPath("backend-live.out.log"))
        assertFalse(UpdatePackageSafety.isAllowedProgramPath("../outside.txt"))
        assertFalse(UpdatePackageSafety.isAllowedProgramPath("/absolute/path.txt"))
    }

    @Test
    fun `semantic versions compare correctly`() {
        assertTrue(UpdateVersionComparator.isNewer("3.0.2", "3.0.1"))
        assertTrue(UpdateVersionComparator.isNewer("v3.1.0", "3.0.9"))
        assertFalse(UpdateVersionComparator.isNewer("3.0.1", "3.0.1"))
        assertFalse(UpdateVersionComparator.isNewer("3.0.0", "3.0.1"))
    }

    @Test
    fun `default update repository points to odds monitor releases`() {
        assertTrue(
            GitHubReleaseApiUrlBuilder.latestReleaseApiUrl(OddsMonitorUpdateDefaults.GITHUB_REPO)
                .endsWith("/Austin-C1/odds-monitor/releases/latest")
        )
    }

    @Test
    fun `github release api url encodes unicode repository names`() {
        assertTrue(
            GitHubReleaseApiUrlBuilder.latestReleaseApiUrl("Austin-C1/全平台赔率监控")
                .endsWith("/Austin-C1/%E5%85%A8%E5%B9%B3%E5%8F%B0%E8%B5%94%E7%8E%87%E7%9B%91%E6%8E%A7/releases/latest")
        )
    }

    @Test
    fun `apply script uses encoded paths and real file list newlines`() {
        val script = UpdateApplyScriptBuilder.render(
            appRoot = Path.of("C:/Users/kesul/Desktop/新建文件夹/_tmp_odds_monitor"),
            packageRoot = Path.of("C:/Users/kesul/Desktop/新建文件夹/_tmp_odds_monitor/updates/work-v3.0.1"),
            backupRoot = Path.of("C:/Users/kesul/Desktop/新建文件夹/_tmp_odds_monitor/backups/update-v3.0.1"),
            files = listOf(
                "backend/build/libs/odds-monitor-backend-3.0.1.jar",
                "frontend/dist/index.html"
            ),
            backendPid = 12345
        )

        assertTrue(script.contains("[Convert]::FromBase64String"))
        assertFalse(script.contains("新建文件夹"))
        assertFalse(script.contains(",`n"))
        assertTrue(script.contains("'backend/build/libs/odds-monitor-backend-3.0.1.jar',"))
        assertTrue(script.contains("'frontend/dist/index.html'"))
        assertTrue(script.contains("Stop-Process -Id 12345"))
        assertTrue(script.contains("launch-odds-monitor.ps1"))
        assertTrue(script.contains("update-apply.log"))
        assertTrue(script.contains("更新失败"))
        assertTrue(script.contains("finally"))
    }
}
