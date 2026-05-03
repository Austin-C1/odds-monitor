package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.UserCreateRequest
import com.wrbug.polymarketbot.dto.UserDto
import com.wrbug.polymarketbot.dto.UserUpdatePasswordRequest
import com.wrbug.polymarketbot.entity.User
import com.wrbug.polymarketbot.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository
) {
    
    private val logger = LoggerFactory.getLogger(UserService::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()
    
    fun isDefaultUser(username: String): Boolean {
        val user = userRepository.findByUsername(username) ?: return false
        return user.isDefault
    }
    
    fun getUserList(currentUsername: String): List<UserDto> {
        val isDefault = isDefaultUser(currentUsername)
        
        if (isDefault) {
            val users = userRepository.findAllByOrderByCreatedAtAsc()
            return users.map { user ->
                UserDto(
                    id = user.id!!,
                    username = user.username,
                    isDefault = user.isDefault,
                    createdAt = user.createdAt,
                    updatedAt = user.updatedAt
                )
            }
        } else {
            val user = userRepository.findByUsername(currentUsername)
            if (user != null) {
                return listOf(
                    UserDto(
                        id = user.id!!,
                        username = user.username,
                        isDefault = user.isDefault,
                        createdAt = user.createdAt,
                        updatedAt = user.updatedAt
                    )
                )
            }
            return emptyList()
        }
    }
    
    @Transactional
    fun createUser(request: UserCreateRequest, currentUsername: String): Result<UserDto> {
        return try {
            val currentUser = userRepository.findByUsername(currentUsername)
                ?: return Result.failure(IllegalArgumentException("当前用户不存在"))
            
            if (!currentUser.isDefault) {
                logger.warn("非默认账户尝试创建用户：currentUser=$currentUsername")
                return Result.failure(IllegalStateException("只有默认账户可以创建用户"))
            }
            if (request.username.isBlank()) {
                return Result.failure(IllegalArgumentException("用户名不能为空"))
            }
            if (request.password.length < 6) {
                return Result.failure(IllegalArgumentException("密码长度不符合要求，至少6位"))
            }
            if (userRepository.existsByUsername(request.username)) {
                return Result.failure(IllegalArgumentException("用户名已存在"))
            }
            val encodedPassword = passwordEncoder.encode(request.password)
            val newUser = User(
                username = request.username,
                password = encodedPassword,
                isDefault = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val savedUser = userRepository.save(newUser)
            
            logger.info("创建用户成功：username=${request.username}, createdBy=$currentUsername")
            Result.success(UserDto(
                id = savedUser.id!!,
                username = savedUser.username,
                isDefault = savedUser.isDefault,
                createdAt = savedUser.createdAt,
                updatedAt = savedUser.updatedAt
            ))
        } catch (e: Exception) {
            logger.error("创建用户异常：username=${request.username}", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun updateUserPassword(request: UserUpdatePasswordRequest, currentUsername: String): Result<Unit> {
        return try {
            val currentUser = userRepository.findByUsername(currentUsername)
                ?: return Result.failure(IllegalArgumentException("当前用户不存在"))
            
            if (!currentUser.isDefault) {
                logger.warn("非默认账户尝试更新用户密码：currentUser=$currentUsername")
                return Result.failure(IllegalStateException("只有默认账户可以更新用户密码"))
            }
            if (request.newPassword.length < 6) {
                return Result.failure(IllegalArgumentException("密码长度不符合要求，至少6位"))
            }
            val targetUser = userRepository.findById(request.userId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("用户不存在"))
            if (targetUser.isDefault) {
                return Result.failure(IllegalArgumentException("不能修改默认账户的密码"))
            }
            val encodedPassword = passwordEncoder.encode(request.newPassword)
            val updatedUser = targetUser.copy(
                password = encodedPassword,
                tokenVersion = targetUser.tokenVersion + 1,
                updatedAt = System.currentTimeMillis()
            )
            userRepository.save(updatedUser)
            
            logger.info("更新用户密码成功：userId=${request.userId}, username=${targetUser.username}, updatedBy=$currentUsername, tokenVersion=${updatedUser.tokenVersion}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("更新用户密码异常：userId=${request.userId}, currentUser=$currentUsername", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun updateOwnPassword(newPassword: String, currentUsername: String): Result<Unit> {
        return try {
            if (newPassword.length < 6) {
                return Result.failure(IllegalArgumentException("密码长度不符合要求，至少6位"))
            }
            val user = userRepository.findByUsername(currentUsername)
                ?: return Result.failure(IllegalArgumentException("用户不存在"))
            val encodedPassword = passwordEncoder.encode(newPassword)
            val updatedUser = user.copy(
                password = encodedPassword,
                tokenVersion = user.tokenVersion + 1,
                updatedAt = System.currentTimeMillis()
            )
            userRepository.save(updatedUser)
            
            logger.info("用户修改自己密码成功：username=$currentUsername, userId=${user.id}, tokenVersion=${updatedUser.tokenVersion}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("用户修改自己密码异常：username=$currentUsername", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun deleteUser(userId: Long, currentUsername: String): Result<Unit> {
        return try {
            val currentUser = userRepository.findByUsername(currentUsername)
                ?: return Result.failure(IllegalArgumentException("当前用户不存在"))
            
            if (!currentUser.isDefault) {
                logger.warn("非默认账户尝试删除用户：currentUser=$currentUsername")
                return Result.failure(IllegalStateException("只有默认账户可以删除用户"))
            }
            val targetUser = userRepository.findById(userId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("用户不存在"))
            if (targetUser.isDefault) {
                return Result.failure(IllegalArgumentException("不能删除默认账户"))
            }
            if (targetUser.username == currentUsername) {
                return Result.failure(IllegalArgumentException("不能删除自己"))
            }
            userRepository.delete(targetUser)
            
            logger.info("删除用户成功：userId=$userId, username=${targetUser.username}, deletedBy=$currentUsername")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除用户异常：userId=$userId, currentUser=$currentUsername", e)
            Result.failure(e)
        }
    }
}

