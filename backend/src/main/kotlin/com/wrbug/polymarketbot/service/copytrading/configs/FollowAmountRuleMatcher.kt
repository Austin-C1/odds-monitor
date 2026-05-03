package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.util.gte
import com.wrbug.polymarketbot.util.lte
import java.math.BigDecimal

data class FollowAmountRuleInput(
    val minLeaderAmount: BigDecimal,
    val maxLeaderAmount: BigDecimal?,
    val followAmount: BigDecimal,
    val followMaxAmount: BigDecimal,
    val sortOrder: Int
)

class FollowAmountRuleMatcher {

    fun match(
        leaderOrderAmount: BigDecimal,
        rules: List<FollowAmountRuleInput>
    ): FollowAmountRuleInput? {
        return rules
            .sortedBy { it.sortOrder }
            .firstOrNull { rule ->
                leaderOrderAmount.gte(rule.minLeaderAmount) &&
                    (rule.maxLeaderAmount == null || leaderOrderAmount.lte(rule.maxLeaderAmount))
            }
    }
}
