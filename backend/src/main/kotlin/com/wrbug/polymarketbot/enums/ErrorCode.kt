package com.wrbug.polymarketbot.enums

enum class ErrorCode(
    val code: Int,
    val message: String,
    val messageKey: String
) {
    PARAM_ERROR(1001, "参数错误", "error.param.error"),
    PARAM_EMPTY(1002, "参数不能为空", "error.param.empty"),
    PARAM_INVALID(1003, "参数无效", "error.param.invalid"),

    AUTH_ERROR(2001, "认证失败", "error.auth.error"),
    AUTH_TOKEN_INVALID(2002, "认证令牌无效", "error.auth.token_invalid"),
    AUTH_TOKEN_EXPIRED(2003, "认证令牌已过期", "error.auth.token_expired"),
    AUTH_PERMISSION_DENIED(2004, "权限不足", "error.auth.permission_denied"),
    AUTH_USERNAME_OR_PASSWORD_ERROR(2009, "用户名或密码错误", "error.auth.username_or_password_error"),
    AUTH_RESET_KEY_INVALID(2010, "重置密钥错误", "error.auth.reset_key_invalid"),
    AUTH_RESET_PASSWORD_RATE_LIMIT(2011, "频率限制，1 分钟内最多尝试 5 次，请稍后再试", "error.auth.reset_password_rate_limit"),
    AUTH_USER_NOT_FOUND(2012, "用户不存在", "error.auth.user_not_found"),
    AUTH_PASSWORD_WEAK(2013, "密码长度不符合要求，至少 6 位", "error.auth.password_weak"),

    NOT_FOUND(3001, "资源不存在", "error.not_found"),
    BUSINESS_ERROR(4001, "业务逻辑错误", "error.business.error"),

    NOTIFICATION_CONFIG_NOT_FOUND(4601, "通知配置不存在", "error.notification_config_not_found"),
    NOTIFICATION_CONFIG_ID_EMPTY(4602, "配置 ID 不能为空", "error.notification_config_id_empty"),
    NOTIFICATION_CONFIG_TYPE_EMPTY(4603, "推送类型不能为空", "error.notification_config_type_empty"),
    NOTIFICATION_CONFIG_NAME_EMPTY(4604, "配置名称不能为空", "error.notification_config_name_empty"),
    NOTIFICATION_CONFIG_DATA_EMPTY(4605, "配置信息不能为空", "error.notification_config_data_empty"),
    NOTIFICATION_CONFIG_BOT_TOKEN_EMPTY(4606, "Bot Token 不能为空", "error.notification_config_bot_token_empty"),
    NOTIFICATION_CONFIG_CREATE_FAILED(4607, "创建配置失败", "error.notification_config_create_failed"),
    NOTIFICATION_CONFIG_UPDATE_FAILED(4608, "更新配置失败", "error.notification_config_update_failed"),
    NOTIFICATION_CONFIG_DELETE_FAILED(4609, "删除配置失败", "error.notification_config_delete_failed"),
    NOTIFICATION_CONFIG_UPDATE_ENABLED_FAILED(4610, "更新启用状态失败", "error.notification_config_update_enabled_failed"),
    NOTIFICATION_CONFIG_FETCH_FAILED(4611, "获取配置失败", "error.notification_config_fetch_failed"),
    NOTIFICATION_TEST_FAILED(4612, "发送测试消息失败，请检查配置", "error.notification_test_failed"),
    NOTIFICATION_GET_CHAT_IDS_FAILED(4613, "获取 Chat IDs 失败", "error.notification_get_chat_ids_failed"),

    SERVER_ERROR(5001, "服务器内部错误", "error.server.error"),
    SERVER_DATABASE_ERROR(5002, "数据库错误", "error.server.database_error"),
    SERVER_NETWORK_ERROR(5003, "网络错误", "error.server.network_error"),
    SERVER_TIMEOUT(5004, "请求超时", "error.server.timeout"),
    SERVER_EXTERNAL_API_ERROR(5005, "外部接口调用失败", "error.server.external_api_error");

    companion object {
        fun fromCode(code: Int): ErrorCode? {
            return entries.find { it.code == code }
        }
    }
}
