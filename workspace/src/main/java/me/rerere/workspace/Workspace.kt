package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
)

/**
 * Rootfs 内文件的分段读取结果(用于大文件按 offset/length 分片读取)
 *
 * @param start 本次读取覆盖的字节区间起点(实际生效值)
 * @param end 区间终点(不含); [truncated] 为 true 时可用 [nextOffset] 继续读
 * @param totalBytes 文件总字节数
 */
data class RootfsTextSlice(
    val text: String,
    val start: Long,
    val end: Long,
    val totalBytes: Long,
) {
    val nextOffset: Long get() = end
    val truncated: Boolean get() = end < totalBytes
}

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
