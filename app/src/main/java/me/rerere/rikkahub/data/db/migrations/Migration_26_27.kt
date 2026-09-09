package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v26 -> v27: workspaces 表新增 shell_compatibility_mode 列（上游 2.5.1 Shell 兼容模式）。
 *
 * 开启后 proot 启动时设置 PROOT_NO_SECCOMP=1，用于部分 ROM seccomp 不兼容导致
 * 容器无法启动的场景。用 ALTER TABLE + hasColumn 防御式迁移（而非 AutoMigration），
 * 避免恢复备份等场景下列已存在时 duplicate column 崩溃。
 */
object Migration_26_27 : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!hasColumn(db, "workspaces", "shell_compatibility_mode")) {
            db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0")
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
