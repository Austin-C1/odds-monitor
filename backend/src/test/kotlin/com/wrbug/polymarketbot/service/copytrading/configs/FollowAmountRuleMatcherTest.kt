package com.wrbug.polymarketbot.service.copytrading.configs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FollowAmountRuleMatcherTest {

    private val matcher = FollowAmountRuleMatcher()

    @Test
    fun `returns first matching rule for a leader order amount`() {
        val matched = matcher.match(
            leaderOrderAmount = BigDecimal("120"),
            rules = listOf(
                FollowAmountRuleInput(
                    minLeaderAmount = BigDecimal("0"),
                    maxLeaderAmount = BigDecimal("100"),
                    followAmount = BigDecimal("10"),
                    followMaxAmount = BigDecimal("20"),
                    sortOrder = 1
                ),
                FollowAmountRuleInput(
                    minLeaderAmount = BigDecimal("100"),
                    maxLeaderAmount = BigDecimal("500"),
                    followAmount = BigDecimal("50"),
                    followMaxAmount = BigDecimal("100"),
                    sortOrder = 2
                ),
                FollowAmountRuleInput(
                    minLeaderAmount = BigDecimal("500"),
                    maxLeaderAmount = null,
                    followAmount = BigDecimal("100"),
                    followMaxAmount = BigDecimal("200"),
                    sortOrder = 3
                )
            )
        )

        assertEquals(BigDecimal("50"), matched?.followAmount)
        assertEquals(BigDecimal("100"), matched?.followMaxAmount)
        assertEquals(2, matched?.sortOrder)
    }

    @Test
    fun `returns null when no rule matches the leader amount`() {
        val matched = matcher.match(
            leaderOrderAmount = BigDecimal("80"),
            rules = listOf(
                FollowAmountRuleInput(
                    minLeaderAmount = BigDecimal("100"),
                    maxLeaderAmount = BigDecimal("500"),
                    followAmount = BigDecimal("50"),
                    followMaxAmount = BigDecimal("100"),
                    sortOrder = 1
                )
            )
        )

        assertNull(matched)
    }

    @Test
    fun `uses the first row when overlapping rules both match`() {
        val matched = matcher.match(
            leaderOrderAmount = BigDecimal("100"),
            rules = listOf(
                FollowAmountRuleInput(
                    minLeaderAmount = BigDecimal("0"),
                    maxLeaderAmount = BigDecimal("100"),
                    followAmount = BigDecimal("10"),
                    followMaxAmount = BigDecimal("20"),
                    sortOrder = 1
                ),
                FollowAmountRuleInput(
                    minLeaderAmount = BigDecimal("100"),
                    maxLeaderAmount = BigDecimal("500"),
                    followAmount = BigDecimal("50"),
                    followMaxAmount = BigDecimal("100"),
                    sortOrder = 2
                )
            )
        )

        assertEquals(BigDecimal("10"), matched?.followAmount)
        assertEquals(1, matched?.sortOrder)
    }
}
