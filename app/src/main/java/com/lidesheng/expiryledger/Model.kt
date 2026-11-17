package com.lidesheng.expiryledger

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 保质期单位 */
enum class LifeUnit(val label: String) {
    DAY("天"), MONTH("月"), YEAR("年");

    companion object {
        fun from(label: String?): LifeUnit = when (label) {
            "天" -> DAY
            "年" -> YEAR
            else -> MONTH
        }
    }
}

/** 一条台账记录 = 一批货 */
data class Batch(
    val id: Long,
    val barcode: String,
    val name: String,
    val productionDate: LocalDate,
    val shelfLifeValue: Int,
    val unit: LifeUnit,
    val expiryDate: LocalDate,
    val deletedAt: Long?,
    val createdAt: Long,
)

/** 条码缓存：第二次扫码自动带出名称与保质期 */
data class BarcodeInfo(
    val barcode: String,
    val name: String,
    val lastValue: Int?,
    val lastUnit: LifeUnit?,
)

/** 阶段分组：按剩余天数划分 */
enum class Stage(val label: String, val colorArgb: Long) {
    EXPIRED("已过期", 0xFFD32F2FL),
    TODAY("今天到期", 0xFFF4511EL),
    D7("7 天内", 0xFFFF9800L),
    D10("10 天内", 0xFFFFB300L),
    D20("20 天内", 0xFFFFD54FL),
    D30("30 天内", 0xFF42A5F5L),
    D40("40 天内", 0xFF26C6DAL),
    NORMAL("正常", 0xFF9E9E9EL);

    companion object {
        fun of(remainingDays: Long): Stage = when {
            remainingDays < 0 -> EXPIRED
            remainingDays == 0L -> TODAY
            remainingDays <= 7 -> D7
            remainingDays <= 10 -> D10
            remainingDays <= 20 -> D20
            remainingDays <= 30 -> D30
            remainingDays <= 40 -> D40
            else -> NORMAL
        }
    }
}

object Dates {

    /** 到期日 = 生产日期 + 保质期（按日历加，不按 30 天近似） */
    fun computeExpiry(production: LocalDate, value: Int, unit: LifeUnit): LocalDate {
        val v = value.coerceAtLeast(1)
        return when (unit) {
            LifeUnit.DAY -> production.plusDays(v.toLong())
            LifeUnit.MONTH -> production.plusMonths(v.toLong())
            LifeUnit.YEAR -> production.plusYears(v.toLong())
        }
    }

    fun remainingDays(expiry: LocalDate, today: LocalDate = LocalDate.now()): Long =
        ChronoUnit.DAYS.between(today, expiry)

    fun formatRemaining(days: Long): String = when {
        days < 0 -> "已过期 ${-days} 天"
        days == 0L -> "今天到期"
        else -> "剩余 $days 天"
    }

    fun parseDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            null
        }
    }
}

/** ML Kit 识别出来的文本里，找生产日期与保质期 */
object TextExtract {

    private val datePatterns = listOf(
        Regex("""(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?"""),
        Regex("""(20\d{2})(\d{2})(\d{2})"""),
    )

    private val shelfPatterns = listOf(
        Regex("""保质期[为:：]?\s*(\d{1,3})\s*(天|日|个月|月|年)"""),
        Regex("""保质期[为:：]?\s*(\d{1,3})"""),
    )

    /** 返回 (生产日期, 保质期数值, 单位) */
    fun extract(raw: String): Extracted {
        val text = raw.replace(" ", "")
        var date: LocalDate? = null
        for (p in datePatterns) {
            val m = p.find(text) ?: continue
            val y = m.groupValues[1].toIntOrNull() ?: continue
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val d = m.groupValues[3].toIntOrNull() ?: continue
            if (y in 2000..2099 && mo in 1..12 && d in 1..31) {
                date = runCatching { LocalDate.of(y, mo, d) }.getOrNull()
                if (date != null) break
            }
        }

        var value: Int? = null
        var unit: LifeUnit? = null
        for (p in shelfPatterns) {
            val m = p.find(text) ?: continue
            value = m.groupValues[1].toIntOrNull()
            val u = if (m.groupValues.size > 2) m.groupValues[2] else ""
            unit = when {
                u.contains("年") -> LifeUnit.YEAR
                u.contains("天") || u.contains("日") -> LifeUnit.DAY
                u.isNotEmpty() -> LifeUnit.MONTH
                else -> null
            }
            break
        }
        return Extracted(date, value, unit)
    }

    data class Extracted(val productionDate: LocalDate?, val value: Int?, val unit: LifeUnit?)
}
