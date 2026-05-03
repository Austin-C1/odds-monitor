package com.wrbug.polymarketbot.util

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

fun BigDecimal.multi(value: Any): BigDecimal {
    kotlin.runCatching {
        if (value is BigDecimal) {
            return multiply(value)
        }
        if (value is BigInteger) {
            return multiply(value.toBigDecimal())
        }
        if (value is Number) {
            return multiply(value.toSafeBigDecimal())
        }
        return multiply(BigDecimal(value.toString()))
    }
    return BigDecimal.ZERO
}


fun BigDecimal.div(value: Any, scale: Int = 18, roundingMode: RoundingMode = RoundingMode.HALF_UP): BigDecimal {
    kotlin.runCatching {
        val divisor = when (value) {
            is BigDecimal -> value
            is BigInteger -> value.toBigDecimal()
            else -> BigDecimal(value.toString())
        }
        return divide(divisor, scale, roundingMode)
    }
    return IllegalBigDecimal
}

fun BigInteger.multi(value: Any): BigDecimal {
    val v = this.toBigDecimal()
    return runCatching {
        v.multi(value)
    }.getOrDefault(IllegalBigDecimal)
}

fun Any?.gt(target: Any?): Boolean {
    if (this == null || target == null) {
        return false
    }
    val thisValue = this.toSafeBigDecimal()
    val targetValue = target.toSafeBigDecimal()
    return thisValue > targetValue
}

fun Any?.gte(target: Any?): Boolean {
    if (this == null || target == null) {
        return false
    }
    val thisValue = this.toSafeBigDecimal()
    val targetValue = target.toSafeBigDecimal()
    return thisValue.compareTo(targetValue) >= 0
}

fun Any?.lt(target: Any?): Boolean {
    if (this == null || target == null) {
        return false
    }
    val thisValue = this.toSafeBigDecimal()
    val targetValue = target.toSafeBigDecimal()
    return thisValue < targetValue
}

fun Any?.lte(target: Any?): Boolean {
    if (this == null || target == null) {
        return false
    }
    val thisValue = this.toSafeBigDecimal()
    val targetValue = target.toSafeBigDecimal()
    return thisValue.compareTo(targetValue) <= 0
}

fun Any?.eq(target: Any?): Boolean {
    if (this == null || target == null) {
        return false
    }
    val thisValue = this.toSafeBigDecimal()
    val targetValue = target.toSafeBigDecimal()
    return thisValue.compareTo(targetValue) == 0
}

fun Any?.neq(target: Any?): Boolean {
    if (this == null || target == null) {
        return false
    }
    val thisValue = this.toSafeBigDecimal()
    val targetValue = target.toSafeBigDecimal()
    return thisValue.compareTo(targetValue) != 0
}

