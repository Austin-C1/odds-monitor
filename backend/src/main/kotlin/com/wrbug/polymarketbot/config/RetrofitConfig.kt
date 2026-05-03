package com.wrbug.polymarketbot.config

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.util.createClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@Configuration
class RetrofitConfig(
    private val gson: Gson
) {
    
    @Bean
    fun polymarketClobApi(): PolymarketClobApi {
        val okHttpClient = createClient().build()
        
        return Retrofit.Builder()
            .baseUrl(PolymarketConstants.CLOB_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketClobApi::class.java)
    }
}

