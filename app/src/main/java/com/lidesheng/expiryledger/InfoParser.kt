package com.lidesheng.expiryledger

import java.time.LocalDate

/**
 * 从一段自由文字里识别「生产日期 + 保质期」。
 * 兼容写法示例：
 *   生产日期2026年8月4日 保质期12个月
 *   2026-08-04 保质 一年半
 *   生产：2026.8.4，有效期18个月
 *   20260804 保质期 540天
 *   8月4日生产 保质期半年
 *   保质期至2027-08-04
 */
object InfoParser {

    data class Parsed(
        val productionDate: LocalDate?,
        val value: Int?,
        val unit: LifeUnit?,
        val expiryDate: LocalDate?,
        val note: String,
        val name: String? = null,
    ) {
        val hasAnything: Boolean
            get() = productionDate != null || (value != null && unit != null) || expiryDate != null
    }

    private val dateRe = listOf(
        // 2026-08-04 / 2026/8/4 / 2026.8.4 / 2026年8月4日
        Regex("""(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*[日号]?"""),
        // 20260804
        Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)"""),
        // 2026年8月（没写日）
        Regex("""(20\d{2})\s*年\s*(\d{1,2})\s*月"""),
        // 8月4日（没写年）
        Regex("""(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]"""),
    )

    private val shelfKeyword = Regex("""(保质期|保质|保存期|保存|有效期|存放期|可保存)""")
    private val expiryKeyword = Regex("""(到期|有效期至|保质期至|保质期到|有效期到|保质至|过期时间|截止)""")

    private val durRe = Regex(
        """(\d{1,4}|[零一二三四五六七八九十两百]{1,4}|半)\s*(个月|月|天|日|年半|年|周|星期)"""
    )

    private val cnDigit = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    fun parse(raw: String): Parsed {
        val text = normalize(raw)
        if (text.isBlank()) return Parsed(null, null, null, null, "没有内容")

        val notes = ArrayList<String>()

        // 1) 先找日期，再按上下文判断它是生产日期还是到期日
        var production: LocalDate? = null
        var expiry: LocalDate? = null

        for (m in dateRe[0].findAll(text)) {
            val (y, mo, d) = triple(m)
            val date = safeDate(y, mo, d) ?: continue
            when {
                isExpiryContext(text, m.range.first) -> expiry = expiry ?: date
                else -> production = production ?: date
            }
        }
        if (production == null && expiry == null) {
            for (m in dateRe[1].findAll(text)) {
                val date = safeDate(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull())
                    ?: continue
                if (isExpiryContext(text, m.range.first)) expiry = expiry ?: date else production = production ?: date
            }
        }
        if (production == null && expiry == null) {
            for (m in dateRe[2].findAll(text)) {
                val date = safeDate(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull(), 1) ?: continue
                if (isExpiryContext(text, m.range.first)) expiry = expiry ?: date else production = production ?: date
            }
        }
        if (production == null && expiry == null) {
            for (m in dateRe[3].findAll(text)) {
                val mo = m.groupValues[1].toIntOrNull() ?: continue
                val d = m.groupValues[2].toIntOrNull() ?: continue
                val today = LocalDate.now()
                var year = today.year
                var date = safeDate(year, mo, d) ?: continue
                if (date.isAfter(today)) {
                    year -= 1
                    date = safeDate(year, mo, d) ?: continue
                }
                if (isExpiryContext(text, m.range.first)) expiry = expiry ?: date else production = production ?: date
            }
        }

        // 2) 找保质期时长
        var value: Int? = null
        var unit: LifeUnit? = null
        // 先把日期片段抹掉，避免把“2028年2月16日”里的数字当成保质期
        val masked = maskDates(text)
        val kw = shelfKeyword.find(masked)
        if (kw != null) {
            val tail = masked.substring(kw.range.last + 1)
            for (dm in durRe.findAll(tail)) {
                val (v, u) = durationOf(dm.groupValues[1], dm.groupValues[2])
                if (v != null && u != null && sane(v, u)) {
                    value = v
                    unit = u
                    break
                }
            }
        }
        // 没写“保质期”字样时，只要全文出现合理的“数字+单位”也认
        if (value == null) {
            for (dm in durRe.findAll(masked)) {
                val (v, u) = durationOf(dm.groupValues[1], dm.groupValues[2])
                if (v != null && u != null && sane(v, u)) {
                    value = v
                    unit = u
                    break
                }
            }
        }

        // 3) 只给了到期日 + 保质期 → 反推生产日期
        if (production == null && expiry != null && value != null && unit != null) {
            val derived = Dates.productionFromExpiry(expiry, value, unit)
            if (derived.year >= 1990) {
                production = derived
                notes += "按到期日反推"
            } else {
                notes += "反推结果不合理，请检查保质期"
            }
        }
        // 4) 只给了生产日期 + 保质期 → 算到期日
        if (expiry == null && production != null && value != null && unit != null) {
            expiry = Dates.computeExpiry(production, value, unit)
        }

        val note = when {
            notes.isNotEmpty() -> notes.joinToString("，")
            production != null && value != null -> "识别到日期和保质期"
            production != null -> "只识别到日期"
            value != null -> "只识别到保质期"
            else -> "没识别到日期或保质期"
        }
        return Parsed(production, value, unit, expiry, note, parseName(raw))
    }

    /**
     * 从粘贴的文字里猜商品名。
     * 常见写法是「王爱林纯净水 NNNNXXCC 保质期18个月」——名字在最前面，
     * 后面跟着批号/编码，再后面是保质期。没有“品名”标签时按这个思路取。
     */
    fun parseName(raw: String): String? {
        val text = raw.replace('\u3000', ' ')
        val cutWords = Regex("""(生产日期|生产|制造|保质期|保质|到期|有效期|净含量|规格|配料|贮存|储存|执行标准|厂名|厂址|地址|电话|SC|QS)""")
        val labelled = Regex("""(?:品名|商品名称|产品名称|产品名|名称)\s*[:：]?\s*([^\n\r,，;；]{2,40})""")
            .find(text)?.groupValues?.get(1)?.trim()
        if (!labelled.isNullOrBlank()) {
            val cut = cutWords.find(labelled)?.range?.first ?: labelled.length
            val v = labelled.substring(0, cut).trim()
            if (v.isNotBlank()) return cleanName(v)
        }

        val lines = text.split(Regex("[\\r\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val longDigits = Regex("""\d{6,}""")          // 20251224 这种日期/批号
        val codes = Regex("""[A-Z]{2,}[A-Z0-9]*""")   // NNNNXXCC 这种纯字母批号
        val nameRun = Regex("""[\u4e00-\u9fa5][\u4e00-\u9fa50-9A-Za-z·\-（）() ]{1,29}""")

        data class Cand(val text: String, val cjk: Int)

        val cands = ArrayList<Cand>()
        for (line in lines) {
            // 1) 关键词处截断（保质期、生产日期等）
            val cut = cutWords.find(line)?.range?.first ?: line.length
            var head = line.substring(0, cut).trim()
            if (head.isEmpty()) continue
            // 2) 抹掉日期片段（2026年9月1日 / 2026-9-1 / 9月1日），不误伤 2026款
            head = stripDates(head).trim()
            if (head.isEmpty()) continue
            // 2.5) 截掉日期/批号（1.5升这类短数字不受影响）
            val d = longDigits.find(head)?.range?.first
            if (d != null && d > 0) head = head.substring(0, d).trim()
            val c = codes.find(head)?.range?.first
            if (c != null && c > 0) head = head.substring(0, c).trim()
            if (head.isEmpty()) continue
            // 3) 剩下的整段就是名字（保留 1.5升 这种规格），只在没有中文时才退回取中文片段
            var name = head.trim().trim(' ', '-', '·', '（', '(', '）', ')')
            // 兜底：名字里还带日期特征，就退回“最长纯中文段”
            if (looksLikeItHasDate(name)) {
                val m = Regex("""[\u4e00-\u9fa5]{2,}""").findAll(name).maxByOrNull { it.value.length }
                name = m?.value ?: ""
            }
            if (name.isBlank() || name.none { it in '\u4e00'..'\u9fa5' }) {
                val m = nameRun.find(head)
                name = (m?.value ?: head).trim().trimEnd('-', '·', ' ', '（', '(')
            }
            val cjk = name.count { it in '\u4e00'..'\u9fa5' }
            if (cjk >= 2 && name.length <= 40) cands += Cand(name, cjk)
        }
        return cands.maxByOrNull { it.cjk }?.let { cleanName(it.text) }
    }

    private fun cleanName(s: String): String =
        s.trim().trim('：', ':', '（', '(', '「', '」', '《', '》', '-', '—', ' ')

    /** 抹掉日期片段：2026年9月1日 / 2026-9-1 / 2026.9.1 / 9月1日 */
    private fun stripDates(s: String): String {
        var out = s
        val extra = listOf(
            Regex("""20\d{2}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*[日号]?"""),
            Regex("""20\d{2}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{1,2}"""),
            Regex("""\d{1,2}\s*月\s*\d{1,2}\s*[日号]"""),
        )
        for (re in dateRe + extra) {
            val spans = re.findAll(out).map { it.range }.toList().asReversed()
            for (span in spans) {
                if (span.first >= out.length) continue
                val end = (span.last + 1).coerceAtMost(out.length)
                out = out.substring(0, span.first) + " " + out.substring(end)
            }
        }
        return out.replace(Regex("""\s+"""), " ").trim()
    }

    /** 名字里是否仍带日期特征 */
    private fun looksLikeItHasDate(s: String): Boolean {
        if (Regex("""\d{1,2}\s*月\s*\d{1,2}\s*[日号]""").containsMatchIn(s)) return true
        if (Regex("""20\d{2}\s*[年./-]\s*\d{1,2}""").containsMatchIn(s)) return true
        if (Regex("""\d{1,2}\s*月""").containsMatchIn(s)) return true
        return false
    }

    /** 把日期片段替换成等长空格，方便后面只找“保质期时长” */
    private fun maskDates(text: String): String {
        var out = text
        for (re in dateRe) {
            val spans = re.findAll(text).map { it.range }.toList()
            for (span in spans) {
                if (span.first >= out.length) continue
                val end = (span.last + 1).coerceAtMost(out.length)
                out = out.substring(0, span.first) + " ".repeat(end - span.first) + out.substring(end)
            }
        }
        return out
    }

    /** 保质期长短的合理性：年 1..50，月 1..600，天 1..20000 */
    private fun sane(v: Int, u: LifeUnit): Boolean = when (u) {
        LifeUnit.YEAR -> v in 1..50
        LifeUnit.MONTH -> v in 1..600
        LifeUnit.DAY -> v in 1..20000
    }

    private fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            sb.append(
                when (ch) {
                    in '０'..'９' -> ('0' + (ch - '０'))
                    '：' -> ':'
                    '，', '、' -> ','
                    '．' -> '.'
                    '／' -> '/'
                    '－', '—', '–', '〜', '～' -> '-'
                    '\u3000', ' ', '\t', '\n', '\r' -> ' '
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private fun isExpiryContext(text: String, pos: Int): Boolean {
        val from = (pos - 14).coerceAtLeast(0)
        val before = text.substring(from, pos)
        if (expiryKeyword.containsMatchIn(before)) return true
        // “保质期 12个月 至 2027-8-4” 这种：前 30 字里出现“至/到”且不是“生产”
        val from2 = (pos - 30).coerceAtLeast(0)
        val wider = text.substring(from2, pos)
        return (wider.contains("至") || wider.contains("到")) &&
            !wider.contains("生产") && !wider.contains("制造") && !wider.contains("加工")
    }

    private fun triple(m: MatchResult): Triple<Int?, Int?, Int?> = Triple(
        m.groupValues[1].toIntOrNull(),
        m.groupValues[2].toIntOrNull(),
        m.groupValues[3].toIntOrNull(),
    )

    private fun safeDate(y: Int?, mo: Int?, d: Int?): LocalDate? {
        if (y == null || mo == null || d == null) return null
        if (y !in 2000..2099 || mo !in 1..12 || d !in 1..31) return null
        return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
    }

    /** 解析时长，统一换算：年 → 年；半年/一年半 → 月；周 → 天 */
    private fun durationOf(numText: String, unitText: String): Pair<Int?, LifeUnit?> {
        val half = numText == "半"
        val n = if (half) 1 else parseCnNumber(numText) ?: return Pair(null, null)
        return when {
            unitText == "年半" -> Pair((n * 12) + 6, LifeUnit.MONTH)
            unitText == "年" && half -> Pair(6, LifeUnit.MONTH)
            unitText == "年" -> Pair(n, LifeUnit.YEAR)
            unitText == "个月" || unitText == "月" -> Pair(if (half) 6 else n, LifeUnit.MONTH)
            unitText == "周" || unitText == "星期" -> Pair(n * 7, LifeUnit.DAY)
            else -> Pair(n, LifeUnit.DAY)
        }
    }

    private fun parseCnNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.isEmpty()) return null
        var total = 0
        var current = 0
        for (ch in s) {
            when {
                ch == '十' -> {
                    current = if (current == 0) 1 else current
                    total += current * 10
                    current = 0
                }
                ch == '百' -> {
                    current = if (current == 0) 1 else current
                    total += current * 100
                    current = 0
                }
                cnDigit.containsKey(ch) -> current = cnDigit[ch] ?: current
                else -> return null
            }
        }
        return total + current
    }
}
