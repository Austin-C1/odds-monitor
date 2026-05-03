package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.service.largebet.LargeBetRollingAggregator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LargeBetMonitorBeanConfig {
    @Bean
    fun largeBetRollingAggregator(): LargeBetRollingAggregator = LargeBetRollingAggregator()
}
