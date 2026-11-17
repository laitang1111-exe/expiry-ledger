package com.lidesheng.expiryledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

/**
 * 本地 SQLite：batch 表一条 = 一批货，barcode_info 表做条码缓存。
 * deletedAt 非空 = 已处理，进回收站；恢复就是置回 null。
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    companion object {
        private const val NAME = "expiry.db"
        private const val VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE batch (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              barcode TEXT NOT NULL,
              name TEXT NOT NULL,
              productionDate TEXT NOT NULL,
              shelfLifeValue INTEGER NOT NULL,
              shelfLifeUnit TEXT NOT NULL,
              expiryDate TEXT NOT NULL,
              deletedAt INTEGER,
              createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_batch_expiry ON batch(expiryDate)")
        db.execSQL(
            """
            CREATE TABLE barcode_info (
              barcode TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              lastValue INTEGER,
              lastUnit TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 目前只有 v1
    }
}

class LedgerRepository(context: Context) {

    private val helper = DbHelper(context.applicationContext)

    // ───────── 查询 ─────────

    fun listActive(): List<Batch> = query("deletedAt IS NULL", "expiryDate ASC")

    fun listTrash(): List<Batch> = query("deletedAt IS NOT NULL", "deletedAt DESC")

    fun countByStage(today: LocalDate = LocalDate.now()): Map<Stage, Int> {
        val map = mutableMapOf<Stage, Int>()
        for (b in listActive()) {
            val st = Stage.of(Dates.remainingDays(b.expiryDate, today))
            map[st] = (map[st] ?: 0) + 1
        }
        return map
    }

    private fun query(where: String, order: String): List<Batch> {
        val out = ArrayList<Batch>()
        helper.readableDatabase.rawQuery(
            "SELECT id,barcode,name,productionDate,shelfLifeValue,shelfLifeUnit,expiryDate,deletedAt,createdAt " +
                "FROM batch WHERE $where ORDER BY $order", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Batch(
                    id = c.getLong(0),
                    barcode = c.getString(1),
                    name = c.getString(2),
                    productionDate = LocalDate.parse(c.getString(3)),
                    shelfLifeValue = c.getInt(4),
                    unit = LifeUnit.from(c.getString(5)),
                    expiryDate = LocalDate.parse(c.getString(6)),
                    deletedAt = if (c.isNull(7)) null else c.getLong(7),
                    createdAt = c.getLong(8),
                )
            }
        }
        return out
    }

    fun findBarcode(barcode: String): BarcodeInfo? {
        helper.readableDatabase.rawQuery(
            "SELECT barcode,name,lastValue,lastUnit FROM barcode_info WHERE barcode = ?",
            arrayOf(barcode)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return BarcodeInfo(
                barcode = c.getString(0),
                name = c.getString(1),
                lastValue = if (c.isNull(2)) null else c.getInt(2),
                lastUnit = if (c.isNull(3)) null else LifeUnit.from(c.getString(3)),
            )
        }
    }

    // ───────── 写入 ─────────

    /** 是否已存在同条码 + 同生产日期 + 同保质期的批次（只看未处理的，可排除自己） */
    fun existsSameBatch(
        barcode: String,
        productionDate: LocalDate,
        value: Int,
        unit: LifeUnit,
        excludeId: Long = 0,
    ): Boolean {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM batch WHERE deletedAt IS NULL AND barcode = ? " +
                "AND productionDate = ? AND shelfLifeValue = ? AND shelfLifeUnit = ? " +
                "AND id <> ? LIMIT 1",
            arrayOf(barcode, productionDate.toString(), value.toString(), unit.label, excludeId.toString())
        ).use { c -> return c.moveToFirst() }
    }

    /** 只改生产日期（保质期沿用原值），到期日重算 */
    fun updateProductionDate(id: Long, productionDate: LocalDate) {
        var value = 1
        var unit = LifeUnit.MONTH
        helper.readableDatabase.rawQuery(
            "SELECT shelfLifeValue, shelfLifeUnit FROM batch WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                value = c.getInt(0)
                unit = LifeUnit.from(c.getString(1))
            }
        }
        val expiry = Dates.computeExpiry(productionDate, value, unit)
        val cv = ContentValues().apply {
            put("productionDate", productionDate.toString())
            put("expiryDate", expiry.toString())
        }
        helper.writableDatabase.update("batch", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 改生产日期 / 改保质期：到期日按日历重算 */
    fun updateBatch(id: Long, productionDate: LocalDate, value: Int, unit: LifeUnit) {
        val expiry = Dates.computeExpiry(productionDate, value, unit)
        val cv = ContentValues().apply {
            put("productionDate", productionDate.toString())
            put("shelfLifeValue", value)
            put("shelfLifeUnit", unit.label)
            put("expiryDate", expiry.toString())
        }
        helper.writableDatabase.update("batch", cv, "id = ?", arrayOf(id.toString()))
    }

    fun addBatch(
        barcode: String,
        name: String,
        productionDate: LocalDate,
        value: Int,
        unit: LifeUnit,
    ): Long {
        val expiry = Dates.computeExpiry(productionDate, value, unit)
        val cv = ContentValues().apply {
            put("barcode", barcode)
            put("name", name)
            put("productionDate", productionDate.toString())
            put("shelfLifeValue", value)
            put("shelfLifeUnit", unit.label)
            put("expiryDate", expiry.toString())
            putNull("deletedAt")
            put("createdAt", System.currentTimeMillis())
        }
        val id = helper.writableDatabase.insert("batch", null, cv)

        val info = ContentValues().apply {
            put("barcode", barcode)
            put("name", name)
            put("lastValue", value)
            put("lastUnit", unit.label)
        }
        helper.writableDatabase.insertWithOnConflict(
            "barcode_info", null, info, SQLiteDatabase.CONFLICT_REPLACE
        )
        return id
    }

    /** 已处理 → 进回收站 */
    fun markProcessed(id: Long) {
        val cv = ContentValues().apply { put("deletedAt", System.currentTimeMillis()) }
        helper.writableDatabase.update("batch", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 回收站恢复 */
    fun restore(id: Long) {
        val cv = ContentValues().apply { putNull("deletedAt") }
        helper.writableDatabase.update("batch", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 彻底删除 */
    fun hardDelete(id: Long) {
        helper.writableDatabase.delete("batch", "id = ?", arrayOf(id.toString()))
    }
}
