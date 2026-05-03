package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.system.ApiHealthCheckService
import com.wrbug.polymarketbot.service.system.ProxyConfigService
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/system/proxy")
class ProxyConfigController(
    private val proxyConfigService: ProxyConfigService,
    private val apiHealthCheckService: ApiHealthCheckService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(ProxyConfigController::class.java)
    
    @PostMapping("/get")
    fun getProxyConfig(): ResponseEntity<ApiResponse<ProxyConfigDto?>> {
        return try {
            val config = proxyConfigService.getProxyConfig()
            ResponseEntity.ok(ApiResponse.success(config))
        } catch (e: Exception) {
            logger.error("获取代理配置失败", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "获取代理配置失败：${e.message}", messageSource))
        }
    }
    
    @PostMapping("/list")
    fun getAllProxyConfigs(): ResponseEntity<ApiResponse<List<ProxyConfigDto>>> {
        return try {
            val configs = proxyConfigService.getAllProxyConfigs()
            ResponseEntity.ok(ApiResponse.success(configs))
        } catch (e: Exception) {
            logger.error("获取代理配置列表失败", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "获取代理配置列表失败：${e.message}", messageSource))
        }
    }
    
    @PostMapping("/http/save")
    fun saveHttpProxyConfig(@RequestBody request: HttpProxyConfigRequest): ResponseEntity<ApiResponse<ProxyConfigDto>> {
        return try {
            val result = proxyConfigService.saveHttpProxyConfig(request)
            if (result.isSuccess) {
                ResponseEntity.ok(ApiResponse.success(result.getOrNull()))
            } else {
                val error = result.exceptionOrNull()
                logger.error("保存 HTTP 代理配置失败", error)
                ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, error?.message ?: "保存失败", messageSource))
            }
        } catch (e: Exception) {
            logger.error("保存 HTTP 代理配置异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "保存 HTTP 代理配置失败：${e.message}", messageSource))
        }
    }
    
    @PostMapping("/check")
    fun checkProxy(): ResponseEntity<ApiResponse<ProxyCheckResponse>> {
        return try {
            val result = proxyConfigService.checkProxy()
            ResponseEntity.ok(ApiResponse.success(result))
        } catch (e: Exception) {
            logger.error("代理检查失败", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "代理检查失败：${e.message}", messageSource))
        }
    }
    
    @PostMapping("/delete")
    fun deleteProxyConfig(@RequestBody request: Map<String, Long>): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val id = request["id"] ?: return ResponseEntity.ok(
                ApiResponse.error(ErrorCode.PARAM_ERROR, "参数错误：缺少 id", messageSource)
            )
            
            val result = proxyConfigService.deleteProxyConfig(id)
            if (result.isSuccess) {
                ResponseEntity.ok(ApiResponse.success(Unit))
            } else {
                val error = result.exceptionOrNull()
                logger.error("删除代理配置失败：id=$id", error)
                ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, error?.message ?: "删除失败", messageSource))
            }
        } catch (e: Exception) {
            logger.error("删除代理配置异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "删除代理配置失败：${e.message}", messageSource))
        }
    }
    
    @PostMapping("/api-health-check")
    fun checkApiHealth(): ResponseEntity<ApiResponse<ApiHealthCheckResponse>> {
        return try {
            val result = runBlocking { apiHealthCheckService.checkAllApis() }
            ResponseEntity.ok(ApiResponse.success(result))
        } catch (e: Exception) {
            logger.error("API 健康检查失败", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "API 健康检查失败：${e.message}", messageSource))
        }
    }
}





