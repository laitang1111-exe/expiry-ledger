package com.lidesheng.expiryledger

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** 数据导出 / 导入（JSON 文本，包含回收站） */
object Backup {

    data class ImportResult(val inserted: Int, val skipped: Int, val failed: Int)

    fun exportJson(repo: LedgerRepository): String {
        val arr = JSONArray()
        for (b in repo.listAll()) {
            arr.put(JSONObject().apply {
                put("barcode", b.barcode)
                put("name", b.name)
                put("productionDate", b.productionDate.toString())
                put("shelfLifeValue", b.shelfLifeValue)
                put("shelfLifeUnit", b.unit.label)
                put("expiryDate", b.expiryDate.toString())
                if (b.deletedAt == null) put("deletedAt", JSONObject.NULL) else put("deletedAt", b.deletedAt)
                put("createdAt", b.createdAt)
            })
        }
        return JSONObject().apply {
            put("app", "expiry_ledger")
            put("version", 1)
            put("exportedAt", LocalDateTime.now().toString())
            put("count", arr.length())
            put("batches", arr)
        }.toString(2)
    }

    fun importJson(repo: LedgerRepository, text: String): ImportResult {
        val arr: JSONArray = try {
            JSONArray(text.trim())
        } catch (_: Exception) {
            try {
                JSONObject(text.trim()).getJSONArray("batches")
            } catch (_: Exception) {
                return ImportResult(0, 0, 1)
            }
        }

        var inserted = 0
        var skipped = 0
        var failed = 0

        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val barcode = o.optString("barcode").trim()
                val name = o.optString("name").trim().ifBlank { "未命名商品" }
                val prod = LocalDate.parse(o.optString("productionDate"))
                val value = o.optInt("shelfLifeValue", 0)
                val unit = LifeUnit.from(o.optString("shelfLifeUnit"))
                val deletedAt = if (o.isNull("deletedAt")) null else o.optLong("deletedAt")
                val createdAt = if (o.isNull("createdAt")) null else o.optLong("createdAt")

                if (barcode.isBlank() || value <= 0) {
                    failed++
                    continue
                }
                if (repo.importOne(barcode, name, prod, value, unit, deletedAt, createdAt)) {
                    inserted++
                } else {
                    skipped++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        return ImportResult(inserted, skipped, failed)
    }
}

/**
 * 每做一次保存（新增/编辑/已处理/恢复/彻底删除/导入）就自动拍一份快照，
 * 写到手机根目录的 /sdcard/赏味期/ 下，文件名带时间和动作。
 */
object AutoBackup {

    const val DIR_NAME = "赏味期"

    fun dir(): java.io.File =
        java.io.File(android.os.Environment.getExternalStorageDirectory(), DIR_NAME)

    fun canWrite(): Boolean = android.os.Environment.isExternalStorageManager()

    /** 最近一次备份文件（没有则 null） */
    fun latestFile(): java.io.File? {
        val d = dir()
        if (!d.isDirectory) return null
        return d.listFiles { f -> f.isFile && f.name.startsWith("赏味期_") && f.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
    }

    /** 拍一份快照，返回写入的文件（失败返回 null） */
    fun snapshot(repo: LedgerRepository, action: String): java.io.File? {
        if (!canWrite()) return null
        return try {
            val d = dir()
            if (!d.exists()) d.mkdirs()
            val now = java.time.LocalDateTime.now()
            val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val safeAction = action.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
            val file = java.io.File(d, "赏味期_${stamp}_${safeAction}.json")
            val text = Backup.exportJson(repo)
            file.writeText(text, Charsets.UTF_8)
            java.io.File(d, "最新.json").writeText(text, Charsets.UTF_8)
            file
        } catch (_: Exception) {
            null
        }
    }
}
