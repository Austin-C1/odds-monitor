package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<User, Long> {
    
    fun findByUsername(username: String): User?
    
    fun existsByUsername(username: String): Boolean
    
    fun findByIsDefaultTrue(): User?
    
    fun findAllByOrderByCreatedAtAsc(): List<User>
}

