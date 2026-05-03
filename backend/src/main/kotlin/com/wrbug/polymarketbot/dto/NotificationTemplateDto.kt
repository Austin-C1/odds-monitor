package com.wrbug.polymarketbot.dto

data class NotificationTemplateDto(
    val id: Long? = null,
    val templateType: String,
    val templateContent: String,
    val isDefault: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class TemplateVariableDto(
    val key: String,
    val category: String,
    val sortOrder: Int = 0
)

data class TemplateVariableCategoryDto(
    val key: String,
    val sortOrder: Int = 0
)

data class TemplateVariablesResponse(
    val templateType: String,
    val categories: List<TemplateVariableCategoryDto>,
    val variables: List<TemplateVariableDto>
)

data class UpdateTemplateRequest(
    val templateContent: String
)

data class TestTemplateRequest(
    val templateType: String,
    val templateContent: String? = null
)

data class TemplateTypeInfoDto(
    val type: String,
    val name: String,
    val description: String
)
