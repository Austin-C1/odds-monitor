package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.entity.ProxyConfig
import java.net.InetSocketAddress
import java.net.Proxy

object ProxyConfigProvider {
    @Volatile
    private var proxyConfig: ProxyConfig? = null
    
    fun setProxyConfig(config: ProxyConfig?) {
        proxyConfig = config
    }
    
    fun getProxyConfig(): ProxyConfig? = proxyConfig
    
    fun getProxy(): Proxy? {
        val config = proxyConfig ?: return null
        if (!config.enabled) {
            return null
        }
        if (config.type != "HTTP") {
            return null
        }
        if (config.host == null || config.port == null) {
            return null
        }
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port))
    }
    
    fun getProxyUsername(): String? = proxyConfig?.username
    
    fun getProxyPassword(): String? = proxyConfig?.password
}

