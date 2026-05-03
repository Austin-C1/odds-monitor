package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.FilteredOrderDto
import com.wrbug.polymarketbot.dto.FilteredOrderListRequest
import com.wrbug.polymarketbot.dto.FilteredOrderListResponse
import com.wrbug.polymarketbot.entity.FilteredOrder
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.FilteredOrderRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class FilteredOrderService(
    private val filteredOrderRepository: FilteredOrderRepository,
    private val accountRepository: AccountRepository,
    private val leaderRepository: LeaderRepository
) {
    
    fun getFilteredOrders(request: FilteredOrderListRequest): FilteredOrderListResponse {
        val page = (request.page ?: 1).coerceAtLeast(1)
        val limit = (request.limit ?: 20).coerceAtMost(100).coerceAtLeast(1)
        val pageable: Pageable = PageRequest.of(page - 1, limit, Sort.by(Sort.Direction.DESC, "createdAt"))
        
        val pageResult = if (request.startTime != null && request.endTime != null) {
            filteredOrderRepository.findByCopyTradingIdAndTimeRange(
                copyTradingId = request.copyTradingId,
                startTime = request.startTime,
                endTime = request.endTime,
                pageable = pageable
            )
        } else if (request.filterType != null) {
            filteredOrderRepository.findByCopyTradingIdAndFilterTypeOrderByCreatedAtDesc(
                copyTradingId = request.copyTradingId,
                filterType = request.filterType,
                pageable = pageable
            )
        } else {
            filteredOrderRepository.findByCopyTradingIdOrderByCreatedAtDesc(
                copyTradingId = request.copyTradingId,
                pageable = pageable
            )
        }
        
        val dtos = pageResult.content.map { entity ->
            convertToDto(entity)
        }
        
        return FilteredOrderListResponse(
            list = dtos,
            total = pageResult.totalElements,
            page = page,
            limit = limit
        )
    }
    
    private fun convertToDto(entity: FilteredOrder): FilteredOrderDto {
        val account = accountRepository.findById(entity.accountId).orElse(null)
        val leader = leaderRepository.findById(entity.leaderId).orElse(null)
        
        return FilteredOrderDto(
            id = entity.id!!,
            copyTradingId = entity.copyTradingId,
            accountId = entity.accountId,
            accountName = account?.accountName,
            leaderId = entity.leaderId,
            leaderName = leader?.leaderName,
            leaderTradeId = entity.leaderTradeId,
            marketId = entity.marketId,
            marketTitle = entity.marketTitle,
            marketSlug = entity.marketSlug,
            side = entity.side,
            outcomeIndex = entity.outcomeIndex,
            outcome = entity.outcome,
            price = entity.price.toString(),
            size = entity.size.toString(),
            calculatedQuantity = entity.calculatedQuantity?.toString(),
            filterReason = entity.filterReason,
            filterType = entity.filterType,
            createdAt = entity.createdAt
        )
    }
    
    fun countFilteredOrders(copyTradingId: Long, filterType: String? = null): Long {
        return if (filterType != null) {
            filteredOrderRepository.countByCopyTradingIdAndFilterType(copyTradingId, filterType)
        } else {
            filteredOrderRepository.countByCopyTradingId(copyTradingId)
        }
    }
}

