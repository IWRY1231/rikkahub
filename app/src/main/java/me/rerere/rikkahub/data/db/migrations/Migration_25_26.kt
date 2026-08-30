package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v25 -> v26: workspaces 表新增 sdcard_subpath 列。
 *
 * 「/sdcard 挂载子目录」配置（直连模式）：如 "Download" → 挂载 /sdcard/Download，
 * 空/NULL = 挂载整个 /sdcard。用 ALTER TABLE + hasColumn 防御式迁移，
 * 避免恢复备份等场景下列已存在时 duplicate column 崩溃。
 */
object Migration_25_26 : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!hasColumn(db, "workspaces", "sdcard_subpath")) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `sdcard_subpath` TEXT")
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        val cursor = db.query("PRAGMA table_info(`$table`)")
        cursor.use {
            while (it.moveToNext()) {
                if (it.getString(it.getColumnIndexOrThrow("name")) == column) return true
            }
        }
        return false
    }
}
