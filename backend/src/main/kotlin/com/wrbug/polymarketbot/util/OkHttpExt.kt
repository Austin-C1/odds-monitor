package com.wrbug.polymarketbot.util

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

fun getProxyConfig(): Proxy? = ProxyConfigProvider.getProxy()

fun createClient(): OkHttpClient.Builder {
    val builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)

    val dbProxy = ProxyConfigProvider.getProxy()
    if (dbProxy != null) {
        builder.proxy(dbProxy)

        val username = ProxyConfigProvider.getProxyUsername()
        val password = ProxyConfigProvider.getProxyPassword()
        if (username != null && password != null) {
            builder.proxyAuthenticator { _, response ->
                val credential = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
    }

    return builder
}
