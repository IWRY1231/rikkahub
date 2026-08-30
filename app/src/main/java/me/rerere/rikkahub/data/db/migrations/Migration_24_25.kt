package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v24 -> v25: workspaces 表新增工作区本地互通两列。
 *
 * - `android_local_access`: 「Android 本地读写工作区与本地互通」开关，默认开启；
 * - `local_directory_uri`: SAF 授权的本地目录 Uri（挂载为 Rootfs 的 /local），可空。
 *
 * 用 ALTER TABLE + hasColumn 防御式迁移（而非 AutoMigration），避免恢复备份等场景下列
 * 已存在时 duplicate column 崩溃。
 */
object Migration_24_25 : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!hasColumn(db, "workspaces", "android_local_access")) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `android_local_access` INTEGER NOT NULL DEFAULT 1")
        }
        if (!hasColumn(db, "workspaces", "local_directory_uri")) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `local_directory_uri` TEXT")
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
