package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    // 【已停用】遗留列：原「Android 本地读写工作区与本地互通」开关 / SAF 本地目录 / /sdcard 挂载子目录。
    // 该能力已整体下线，这三列不再被任何逻辑读取/写入，仅为兼容既有数据库保留
    // （Room 要求实体列与表一致，删列需整表重建迁移，minSdk 26 下 DROP COLUMN 不可用）。
    @ColumnInfo("android_local_access", defaultValue = "1")
    val androidLocalAccess: Boolean = true,
    @ColumnInfo("local_directory_uri")
    val localDirectoryUri: String? = null,
    @ColumnInfo("sdcard_subpath")
    val sdcardSubPath: String? = null,
    // Shell 兼容模式（上游 2.5.1）：proot 启动时设置 PROOT_NO_SECCOMP=1，
    // 用于部分 ROM seccomp 不兼容导致容器无法启动的场景
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
