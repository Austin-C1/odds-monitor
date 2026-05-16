package com.wrbug.polymarketbot.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
class SchedulingConfig {
    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler {
        return ThreadPoolTaskScheduler().apply {
            setPoolSize(4)
            setThreadNamePrefix("scheduled-task-")
            setRemoveOnCancelPolicy(true)
            initialize()
        }
    }
}
