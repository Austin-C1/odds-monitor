package com.wrbug.polymarketbot.service.announcement

import com.wrbug.polymarketbot.dto.AnnouncementDto
import com.wrbug.polymarketbot.dto.AnnouncementListResponse
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class AnnouncementService {

    private val announcements = listOf(
        announcement(
            id = 2026042703L,
            title = "全平台赔率监控最新更新说明",
            body = """
                # 全平台赔率监控最新更新说明

                更新时间：2026-04-27

                ## 本次重点

                这次更新主要围绕三个方向：盘口查询、Telegram 机器人、快捷启动。目标是让查询结果更准确，启动过程更稳定，日常使用更接近普通软件。

                ## 已更新

                ### 盘口投注查询

                - 新增 Telegram 盘口查询功能，可直接在指定机器人里发送比赛名称查询盘口。
                - 盘口查询结果已改为中文展示。
                - O/U 盘口自动显示为“大小”。
                - Yes / No 在大小盘里自动显示为“大 / 小”。
                - 默认只显示比赛主盘口：胜负、让分、总分。
                - 球员得分、篮板、助攻、上半场盘口不再默认混入结果。
                - 总成交额、总挂单金额改为使用 Polymarket 官方口径，减少和页面数据的偏差。

                ### Telegram 机器人

                - 可在后台选择哪些 Telegram 机器人负责响应盘口查询。
                - 查询机器人和监控推送机器人可以分开配置。
                - Leader 分组筛选继续只影响监控推送，不影响盘口查询。

                ### 快捷启动

                - 双击启动程序会自动检查数据库、后端、前端。
                - 已经启动时不会重复打开一堆服务。
                - 前端 API 转发异常时会自动重启前端服务。
                - 后端端口异常时会自动重启后端服务。
                - 登录页 405 问题已修复。
                - 启动失败时会显示原因和日志位置。

                ## 使用建议

                - 日常启动请优先使用 `launch-odds-monitor.cmd`。
                - Telegram 查询机器人建议单独创建，避免和监控推送混在同一个群里。
                - 盘口查询适合快速查看比赛主盘口，不用于直接下单。
                - 如果页面异常，先重新双击启动程序，它会自动检查并修复常见启动问题。

                ## 已验证

                - 后端接口可用。
                - 前端页面可打开。
                - 登录接口可用。
                - Telegram 盘口查询逻辑已更新。
                - GitHub 最新版本已同步。
            """.trimIndent(),
            createdAt = Instant.parse("2026-04-27T13:30:00Z").toEpochMilli()
        ),
        announcement(
            id = 2026042702L,
            title = "功能说明书：快捷启动与登录",
            body = """
                # 功能说明书：快捷启动与登录

                适用对象：本地运行全平台赔率监控的用户

                ## 一、启动方式

                推荐使用根目录里的：

                `launch-odds-monitor.cmd`

                双击后程序会自动处理以下内容：

                - 检查数据库是否可用。
                - 检查后端服务是否可用。
                - 检查前端页面是否可用。
                - 检查前端是否能正常转发 API。
                - 自动打开登录页。

                ## 二、正常启动后的状态

                启动成功后会自动打开：

                `http://127.0.0.1:18881/login`

                如果已经登录，可以直接进入后台页面。

                ## 三、重复双击会怎样

                可以重复双击启动程序。

                程序会先检查已有服务：

                - 服务正常：直接打开登录页。
                - 后端异常：自动重启后端。
                - 前端异常：自动重启前端。
                - 数据库未启动：自动尝试启动数据库。

                ## 四、常见问题

                ### 1. 登录时报 405

                原因通常是前端服务没有正确转发 API。

                处理方式：

                - 重新双击 `launch-odds-monitor.cmd`。
                - 程序会检查并重启前端服务。

                ### 2. 页面打不开

                先确认启动窗口是否提示失败。

                常见原因：

                - Docker Desktop 没启动。
                - 数据库端口被占用。
                - 后端启动失败。
                - 前端端口被占用。

                ### 3. 不知道哪里出错

                查看根目录里的日志文件：

                - `backend-live.out.log`
                - `backend-live.err.log`
                - `frontend-live.out.log`
                - `frontend-live.err.log`

                ## 五、建议用法

                - 每次使用前双击 `launch-odds-monitor.cmd` 即可。
                - 不需要手动分别启动前端和后端。
                - 如果页面长时间没有响应，重新双击启动程序。
                - 不建议同时打开多个旧版本目录，避免端口冲突。
            """.trimIndent(),
            createdAt = Instant.parse("2026-04-27T13:20:00Z").toEpochMilli()
        ),
        announcement(
            id = 2026042701L,
            title = "功能说明书：Telegram 机器人与盘口查询",
            body = """
                # 功能说明书：Telegram 机器人与盘口查询

                适用对象：需要用 Telegram 接收提醒、查询盘口的用户

                ## 一、机器人类型

                全平台赔率监控里 Telegram 机器人主要有两种用法：

                - 消息推送机器人：接收监控提醒、同向提醒、反向提醒。
                - 盘口查询机器人：在 Telegram 里输入比赛名称，返回盘口信息。

                两种功能可以放在同一个机器人，也可以分开使用。更推荐分开，消息会更清楚。

                ## 二、如何开启盘口查询

                进入后台：

                `系统管理 -> 盘口投注查询`

                在“查询机器人”里选择允许响应查询的 Telegram 机器人，然后保存。

                只有被选中的机器人会回复盘口查询。

                ## 三、Telegram 查询格式

                可以直接发送比赛名称：

                `Thunder vs Suns`

                也可以使用指令：

                `/盘口 Thunder vs Suns`

                `盘口 Thunder vs Suns`

                `/market Thunder vs Suns`

                如果需要指定日期，可以加日期：

                `Thunder vs Suns 2026-04-27`

                ## 四、查询结果包含什么

                默认返回比赛主盘口：

                - 胜负
                - 让分
                - 总分

                默认不返回：

                - 球员得分盘口
                - 球员篮板盘口
                - 球员助攻盘口
                - 上半场盘口

                这样返回结果会更接近 Polymarket 页面里的“比赛盘口”区域。

                ## 五、中文显示规则

                - O/U 会显示为“大小”。
                - Yes / Over 会显示为“大”。
                - No / Under 会显示为“小”。
                - Liquidity 会显示为“挂单金额”。
                - Volume 会显示为“成交额”。

                ## 六、消息推送和 Leader 分组

                Leader 分组只影响监控推送。

                它不会影响：

                - 盘口查询机器人
                - 大额投注监控
                - 普通订单提醒

                如果一个机器人没有选择 Leader 分组，它会接收全部监控 Leader 的消息。

                如果选择了一个或多个分组，它只接收这些分组的监控消息。

                ## 七、建议配置

                - 单独创建一个机器人负责盘口查询。
                - 单独创建一个机器人负责监控提醒。
                - 大额投注监控建议单独放一个群。
                - 每次改完机器人配置后，先用测试按钮确认 Chat ID 可用。
            """.trimIndent(),
            createdAt = Instant.parse("2026-04-27T13:10:00Z").toEpochMilli()
        )
    )

    suspend fun getAnnouncementList(forceRefresh: Boolean = false): Result<AnnouncementListResponse> {
        val list = if (forceRefresh) announcements else announcements
        return Result.success(
            AnnouncementListResponse(
                list = list,
                hasMore = false,
                total = list.size
            )
        )
    }

    suspend fun getAnnouncementDetail(id: Long?, forceRefresh: Boolean = false): Result<AnnouncementDto> {
        val list = if (forceRefresh) announcements else announcements
        val announcement = if (id == null) {
            list.firstOrNull()
        } else {
            list.find { it.id == id }
        } ?: return Result.failure(IllegalArgumentException("公告不存在"))

        return Result.success(announcement)
    }

    private fun announcement(
        id: Long,
        title: String,
        body: String,
        createdAt: Long
    ): AnnouncementDto {
        return AnnouncementDto(
            id = id,
            title = title,
            body = body,
            author = "全平台赔率监控",
            authorAvatarUrl = null,
            createdAt = createdAt,
            updatedAt = createdAt,
            reactions = null
        )
    }
}
