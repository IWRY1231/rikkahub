package me.rerere.rikkahub.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.WorkspaceMounts
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsTextSlice
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.commandReferencesSdcardPath
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val context: Context,
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    /** 按工作区 root 名查询（终端会话等只有 root 的场景使用） */
    suspend fun getByRoot(root: String): WorkspaceEntity? = dao.getByRoot(root)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 设置工作区的 /sdcard 挂载子目录（直连模式）。传 null/空白 = 挂载整个 /sdcard。 */
    suspend fun setSdcardSubPath(id: String, subPath: String?): Boolean {
        val workspace = dao.getById(id) ?: return false
        val cleaned = subPath?.trim()?.trim('/')
            ?.split('/')?.filter { it.isNotBlank() && it != "." && it != ".." }
            ?.joinToString("/")
            ?.takeIf { it.isNotEmpty() }
        dao.updateSdcardSubPath(id, cleaned, System.currentTimeMillis())
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.resolveFile(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /**
     * 工作区的 /sdcard 直连挂载（按子目录配置; 未配置 = 挂载整盘）。
     * 注 ①: 原「本地互通」总开关已停用（恒开启），故此处不再有开关判定;
     * 注 ②: 未授予「所有文件访问」权限时**整体撤下挂载** —— 否则容器里会留下一个"看得见但读不出"的
     *        空目录(绑定能建立, readdir 被 FUSE 拒绝), 工具与模型会误判成"目录本来就是空的"。
     */
    private fun sdcardBind(workspace: WorkspaceEntity): WorkspaceBindMount? =
        if (allFilesAccessGranted()) WorkspaceMounts.sdcardMount(workspace.sdcardSubPath) else null

    /** 「所有文件访问」是否已授予（/sdcard 可用性的单一事实源; 提示词与终端也用它） */
    fun allFilesAccessGranted(): Boolean = WorkspaceMounts.allFilesAccessGranted(context)

    /**
     * 未授予「所有文件访问」时, 任何 /sdcard 路径在入口处显式报错。
     *
     * 这是"响亮失败"而不是额外限制: 权限被收回后 proot 绑定仍可能建立, 但 readdir 会被 FUSE 拒绝,
     * 表现为**空目录**(不报错) —— 模型会据此得出"手机里没有这些文件"的错误结论。宁可直接报错。
     * 注: 仅做 `/sdcard` 前缀判定（前缀 + '/' 边界）—— 不会误伤 /usr/share/sdcard-doc 之类;
     *     与"挂载子目录范围"判定无关（后者在 WorkspaceManager.resolveRootfsPath 内）。
     */
    private fun requireSdcardAccess(path: String) {
        if (path == "/sdcard" || path.startsWith("/sdcard/")) {
            check(allFilesAccessGranted()) { WorkspaceMounts.SDCARD_PERMISSION_REQUIRED_MESSAGE }
        }
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、各挂载点与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        requireSdcardAccess(path)
        manager.rootfsFileSize(
            workspace.root, path,
            extraBindMounts = listOfNotNull(sdcardBind(workspace)),
        )
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、各挂载点与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        requireSdcardAccess(path)
        manager.exportRootfsFile(
            workspace.root, path, outputStream,
            extraBindMounts = listOfNotNull(sdcardBind(workspace)),
        )
    }

    /**
     * 按 Rootfs 内绝对路径写入 UTF-8 文本, 直接 Java IO 写入宿主机物理路径, 不经 PRoot,
     * 因此 /sdcard 这类 FUSE 挂载点也能可靠读写。
     */
    suspend fun writeTextInRootfs(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        requireSdcardAccess(path)
        val result = manager.writeRootfsText(
            workspace.root, path, text, overwrite,
            extraBindMounts = listOfNotNull(sdcardBind(workspace)),
        )
        result
    }

    /** 写入二进制内容(如 base64 解码后的数据), 与 [writeTextInRootfs] 同一套路径解析 */
    suspend fun writeBytesInRootfs(
        id: String,
        path: String,
        bytes: ByteArray,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        requireSdcardAccess(path)
        val result = manager.writeRootfsBytes(
            workspace.root, path, bytes, overwrite,
            extraBindMounts = listOfNotNull(sdcardBind(workspace)),
        )
        result
    }

    /** 按 Rootfs 内绝对路径列出目录 */
    suspend fun listInRootfs(
        id: String,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        requireSdcardAccess(path)
        manager.listRootfs(
            workspace.root, path,
            extraBindMounts = listOfNotNull(sdcardBind(workspace)),
        )
    }

    /** 按 Rootfs 内绝对路径删除文件/目录 */
    suspend fun deleteInRootfs(
        id: String,
        path: String,
        recursive: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        requireSdcardAccess(path)
        val deleted = manager.deleteRootfs(
            workspace.root, path, recursive,
            extraBindMounts = listOfNotNull(sdcardBind(workspace)),
        )
        deleted
    }

    /** 按 Rootfs 内绝对路径移动/重命名 */
    suspend fun moveInRootfs(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        requireSdcardAccess(source)
        requireSdcardAccess(target)
        val result = manager.moveRootfs(
            workspace.root, source, target, overwrite,
            extraBindMounts = listOfNotNull(sdcardBind(workspace)),
        )
        result
    }

    /** 分段读取 Rootfs 内文件(大文件按 offset/length 分片) */
    suspend fun readTextRangeInRootfs(
        id: String,
        path: String,
        offset: Long,
        length: Long,
    ): RootfsTextSlice = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        requireSdcardAccess(path)
        manager.readRootfsTextRange(
            workspace.root, path, offset, length,
            extraBindMounts = listOfNotNull(sdcardBind(workspace)),
        )
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // 未授予「所有文件访问」时 /sdcard 完全没有挂载: 命令若引用 /sdcard, 在执行前确定性拦截
        // (与文件工具同一原则: 响亮报错, 不允许先写进沙盒占位目录、事后才发现"手机上什么都没有")
        if (!allFilesAccessGranted() && commandReferencesSdcardPath(command)) {
            return WorkspaceCommandResult(
                exitCode = 126,
                stdout = "",
                stderr = WorkspaceMounts.SDCARD_PERMISSION_REQUIRED_MESSAGE + "\n",
            )
        }
        val extraBindMounts = buildList {
            // 用户配置的 /sdcard 挂载子目录（直连）
            sdcardBind(workspace)?.let { add(it) }
        }
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        val result = runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                workspace.root,
                command,
                cwd,
                timeoutMillis,
                stdin,
                extraBindMounts = extraBindMounts,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
            )
        }
        return result
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
