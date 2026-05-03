package com.wrbug.polymarketbot.util

import java.math.BigDecimal
import java.math.BigInteger

val IllegalBigDecimal = BigDecimal("0")

val IllegalBigInteger = BigInteger("0")

fun Any?.toSafeBigDecimal(): BigDecimal {
    return try {
        if (this is BigDecimal) {
            return this
        }
        if (this is BigInteger) {
            return this.toBigDecimal()
        }
        if (this is Number) {
            return BigDecimal.valueOf(this.toDouble())
        }
        BigDecimal(this.toString())
    } catch (t: Throwable) {
        IllegalBigDecimal
    }
}

fun String?.toSafeBigInteger(): BigInteger {
    return try {
        BigInteger(this.orEmpty())
    } catch (t: Throwable) {
        IllegalBigInteger
    }
}

fun String?.toSafeLong(): Long {
    return try {
        this?.toLong() ?: 0
    } catch (t: Throwable) {
        0
    }
}

fun Any?.toSafeInt(): Int {
    return try {
        if (this is Number) {
            this.toInt()
        } else {
            this?.toString().toSafeBigDecimal().toInt()
        }
    } catch (t: Throwable) {
        0
    }
}

fun Any?.toSafeDouble(): Double {
    return try {
        when (this) {
            is Number -> this.toDouble()
            is Boolean -> if (this) 1.0 else 0.0
            else -> this?.toString().toSafeBigDecimal().toDouble()
        }
    } catch (t: Throwable) {
        0.0
    }
}

