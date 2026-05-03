package com.wrbug.polymarketbot.service.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TelegramNotificationServiceImplementationTest {

    private val sourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "service",
        "system",
        "TelegramNotificationService.kt"
    )
    private val copyOrderTrackingSourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "service",
        "copytrading",
        "statistics",
        "CopyOrderTrackingService.kt"
    )
    private val orderStatusUpdateSourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "service",
        "copytrading",
        "statistics",
        "OrderStatusUpdateService.kt"
    )
    private val leaderMonitorAlertSourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "service",
        "copytrading",
        "statistics",
        "LeaderMonitorAlertService.kt"
    )

    @Test
    fun `telegram notification requests should close okHttp responses with use`() {
        val source = Files.readString(sourcePath)

        assertFalse(
            source.contains("val response = okHttpClient.newCall(request).execute()"),
            "TelegramNotificationService should not leave OkHttp responses open"
        )

        assertTrue(
            source.contains("okHttpClient.newCall(request).execute().use { response ->"),
            "TelegramNotificationService should close OkHttp responses with use"
        )
    }

    @Test
    fun `order failure notifications should support signal source leader name`() {
        val source = Files.readString(sourcePath)
        val orderFailureSendSection = source.substringAfter("suspend fun sendOrderFailureNotification(")
            .substringBefore("private fun buildOrderFailureVariables(")
        val orderFailureSection = source.substringAfter("private fun buildOrderFailureVariables(")
            .substringBefore("suspend fun sendOrderFilteredNotification(")

        assertTrue(
            orderFailureSendSection.contains("leaderName: String? = null"),
            "sendOrderFailureNotification should accept leaderName so failures can show signal source"
        )

        assertTrue(
            source.contains("\"leader_name\" to"),
            "ORDER_FAILED variables should include leader_name for notification templates"
        )

        assertTrue(
            orderFailureSection.contains("val accountInfo = buildAccountInfoWithSignalSource("),
            "ORDER_FAILED account block should include signal source in the rendered Telegram message"
        )

        assertTrue(
            orderFailureSendSection.contains("configName: String? = null"),
            "sendOrderFailureNotification should accept configName so failures can show the configured source name"
        )

        assertTrue(
            source.contains("notification.order.current_position_value"),
            "Telegram notification messages should use a dedicated current position value label"
        )

        assertTrue(
            orderFailureSendSection.contains("currentPositionValue: String? = null"),
            "sendOrderFailureNotification should accept currentPositionValue so failures can show the leader's live position"
        )

        assertTrue(
            orderFailureSection.contains("currentPositionValueLabel = currentPositionValueLabel"),
            "ORDER_FAILED account block should include the current position value label"
        )

        assertTrue(
            orderFailureSection.contains("currentPositionValue = currentPositionValue"),
            "ORDER_FAILED account block should include the current position value"
        )
    }

    @Test
    fun `copy trading buy notifications should pass leader current position value to telegram notifications`() {
        val source = Files.readString(copyOrderTrackingSourcePath)
        val orderStatusSource = Files.readString(orderStatusUpdateSourcePath)

        assertTrue(
            source.contains("leaderName = leader?.leaderName"),
            "CopyOrderTrackingService should pass leader name when sending failure notifications"
        )

        assertTrue(
            source.contains("configName = copyTrading.configName"),
            "CopyOrderTrackingService should pass config name when sending failure notifications"
        )

        assertTrue(
            source.contains("resolveLeaderCurrentPositionValue("),
            "CopyOrderTrackingService should resolve the leader's live position value before sending buy failure notifications"
        )

        assertTrue(
            source.contains("currentPositionValue = leaderCurrentPositionValue"),
            "CopyOrderTrackingService should pass the leader's current position value when sending failure notifications"
        )

        assertTrue(
            orderStatusSource.contains("resolveLeaderCurrentPositionValue("),
            "OrderStatusUpdateService should resolve the leader's live position value before sending success notifications"
        )

        assertTrue(
            orderStatusSource.contains("currentPositionValue = leaderCurrentPositionValue"),
            "OrderStatusUpdateService should pass the leader's current position value when sending success notifications"
        )
    }

    @Test
    fun `monitor push notifications should render dedicated template and send to monitor audience`() {
        val source = Files.readString(sourcePath)

        assertTrue(
            source.contains("suspend fun sendMonitorPushNotification("),
            "TelegramNotificationService should expose a dedicated monitor push sender"
        )

        val monitorPushSection = source.substringAfter("suspend fun sendMonitorPushNotification(")
            .substringBefore("suspend fun sendMonitorSameSideNotification(")

        assertTrue(
            monitorPushSection.contains("renderTemplate(\n            \"MONITOR_PUSH\""),
            "Monitor push notifications should use the MONITOR_PUSH template"
        )

        assertTrue(
            monitorPushSection.contains("sendCopyTradingMessage(message") &&
                monitorPushSection.contains("TelegramNotificationAudience.MONITOR_ONLY"),
            "Monitor push notifications should be delivered only to monitor Telegram configs"
        )

        assertTrue(
            monitorPushSection.contains("systemConfigService.getOrderNotificationMinAmount()") &&
                monitorPushSection.contains("shouldSuppressOrderNotificationAmount(amount, orderNotificationMinAmount)"),
            "Monitor push notifications should use the same minimum amount filter before sending"
        )
    }

    @Test
    fun `same side and opposite monitor notifications should use robot leader group filters`() {
        val source = Files.readString(sourcePath)
        val leaderMonitorSource = Files.readString(leaderMonitorAlertSourcePath)

        val sameSideSection = source.substringAfter("suspend fun sendMonitorSameSideNotification(")
            .substringBefore("suspend fun sendMonitorOppositeNotification(")
        val oppositeSection = source.substringAfter("suspend fun sendMonitorOppositeNotification(")
            .substringBefore("private suspend fun sendTelegramMessage(")
        val publishMarketUpdatesSection = leaderMonitorSource.substringAfter("private suspend fun publishMarketUpdates(")
            .substringBefore("private fun captureBaselineForMarket(")

        assertTrue(
            sameSideSection.contains("leaderGroups: Collection<String?>") &&
                sameSideSection.contains("sendCopyTradingMessage(message, leaderGroups, TelegramNotificationAudience.MONITOR_ONLY)"),
            "Same-side monitor notifications should route through the robot leader-group filters"
        )

        assertTrue(
            oppositeSection.contains("leaderGroups: Collection<String?>") &&
                oppositeSection.contains("sendCopyTradingMessage(message, leaderGroups, TelegramNotificationAudience.MONITOR_ONLY)"),
            "Opposite-side monitor notifications should route through the robot leader-group filters"
        )

        assertTrue(
            publishMarketUpdatesSection.contains("leaderGroups = alert.leaderGroups"),
            "Leader monitor same/opposite alerts should pass their leader groups to Telegram routing"
        )
    }

    @Test
    fun `leader monitor flow should publish a trade detection push notification`() {
        val source = Files.readString(leaderMonitorAlertSourcePath)
        val processTradeSection = source.substringAfter("suspend fun processTrade(leaderId: Long, trade: TradeResponse)")
            .substringBefore("private suspend fun ensureBaseline(")

        assertTrue(
            processTradeSection.contains("sendMonitorPushNotification("),
            "LeaderMonitorAlertService should publish a monitor push notification when a monitored trade is detected"
        )
    }
}
