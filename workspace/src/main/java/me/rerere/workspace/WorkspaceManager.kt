package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun resolveFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): File {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     *
     * [includeAndroidLocal] 关闭时不再解析 Android 本地挂载目录（/skills、/tool_outputs、
     * /upload、/sdcard 等），实现工作区与 Android 本地的隔离。
     */
    fun resolveRootfsPath(
        root: String,
        path: String,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        val mounts = if (includeAndroidLocal) sortedBindMounts else emptyList()
        mounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(
                    rootDir = mount.source,
                    relativePath = trimmed.removePrefix(target).trimStart('/'),
                )
            }
        }

        // 动态附加挂载(如按工作区配置的 /sdcard 子目录), 与静态挂载表同等参与解析
        extraBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(
                    rootDir = mount.source,
                    relativePath = trimmed.removePrefix(target).trimStart('/'),
                )
            }
        }

        // 工作区文件区: Rootfs 内 $ROOTFS_WORKSPACE_DIR 是 filesDir 的绑定挂载(见 ProotShellRunner),
        // 必须在这里解析, 否则会落到 Rootfs 里那个被挂载遮挡的空挂载点(读=文件不存在, 写=EACCES)。
        // 注意: 与 includeAndroidLocal 无关 —— 它属于工作区自身, 不受"本地互通"总开关影响。
        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // /sdcard 访问防护: 未挂载 / 未授权的 /sdcard 路径显式报错, 而不是静默落进沙盒占位目录
        // (权限未授予时 app 层不会传 /sdcard 挂载, 于是这里给出"不可用"的明确原因)
        if (trimmed == "/sdcard" || trimmed.startsWith("/sdcard/")) {
            val sdcardTargets = (mounts + extraBindMounts)
                .map { it.target.trimEnd('/') }
                .filter { it == "/sdcard" || it.startsWith("/sdcard/") }
            if (sdcardTargets.none { target -> trimmed == target || trimmed.startsWith("$target/") }) {
                if (sdcardTargets.isEmpty()) {
                    error(
                        "/sdcard 未挂载到工作区（未授予「所有文件访问」权限, 或挂载配置无效）: " +
                            "\"$trimmed\" 不可访问, 已阻止本次操作(否则会静默写入沙盒占位目录, 文件不会出现在手机上)。" +
                            "请在「工作区详情页 → 所有文件访问」授权后重试。"
                    )
                } else {
                    error(
                        "/sdcard 处于部分挂载模式: 当前仅 ${sdcardTargets.joinToString(", ")} 可访问; " +
                            "\"$trimmed\" 不在挂载范围内 —— 容器内该路径只是沙盒占位(列目录会得到不完整的结果, " +
                            "写入也不会出现在手机上), 已阻止本次操作。可在「工作区详情页 → 挂载子目录」扩大范围。"
                    )
                }
            }
        }

        // /local（旧的 SAF 本地目录镜像通道）已随功能下线**整体移除**：这里显式报错,
        // 避免静默落到 Rootfs 里的 /local 占位目录（那样文件既到不了手机也没人清理）。
        if (trimmed == REMOVED_LOCAL_DIR || trimmed.startsWith("$REMOVED_LOCAL_DIR/")) {
            error(
                "/local is no longer available: the local-folder mirror feature was removed. " +
                    "Use /workspace for the workspace files area, or /sdcard for phone storage."
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }
        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    /** 列出 Rootfs 内绝对路径对应的目录内容(经 [resolveRootfsPath] 解析, 因此各挂载点/守卫一并生效) */
    fun listRootfs(
        root: String,
        path: String,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): List<WorkspaceFileEntry> {
        val location = resolveRootfsPath(root, path, includeAndroidLocal, extraBindMounts)
        return fileSystem.list(location.rootDir, location.relativePath)
    }

    /** 删除 Rootfs 内绝对路径对应的文件/目录; 拒绝删除挂载点根目录本身 */
    fun deleteRootfs(
        root: String,
        path: String,
        recursive: Boolean = false,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): Boolean {
        val location = resolveRootfsPath(root, path, includeAndroidLocal, extraBindMounts)
        require(location.relativePath.isNotBlank()) { "Refusing to delete a mount root: $path" }
        return fileSystem.delete(location.rootDir, location.relativePath, recursive)
    }

    /**
     * 移动/重命名 Rootfs 内绝对路径。
     * 同一挂载点内走 rename; 跨挂载点(如 /sdcard → /workspace)仅支持文件(复制后删除源),
     * 目录跨挂载点请改用 shell (`mv`), 避免隐式的大批量复制。
     */
    fun moveRootfs(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceFileEntry {
        val from = resolveRootfsPath(root, source, includeAndroidLocal, extraBindMounts)
        val to = resolveRootfsPath(root, target, includeAndroidLocal, extraBindMounts)
        require(to.relativePath.isNotBlank()) { "Refusing to overwrite a mount root: $target" }
        if (from.rootDir.canonicalPath == to.rootDir.canonicalPath) {
            return fileSystem.move(from.rootDir, from.relativePath, to.relativePath, overwrite)
        }
        val sourceFile = fileSystem.resolve(from.rootDir, from.relativePath)
        require(sourceFile.isFile) {
            "Cannot move a directory across mounts ($source -> $target), use workspace_shell with mv instead"
        }
        val targetFile = fileSystem.resolve(to.rootDir, to.relativePath)
        require(!targetFile.exists() || overwrite) { "Target already exists: $target" }
        if (targetFile.exists()) targetFile.delete()
        val entry = fileSystem.importBytes(to.rootDir, to.relativePath, sourceFile.inputStream())
        fileSystem.delete(from.rootDir, from.relativePath, recursive = false)
        return entry
    }

    /** 分段读取 Rootfs 内文件(大文件按 offset/length 分片, 不做 maxReadBytes 限制) */
    fun readRootfsTextRange(
        root: String,
        path: String,
        offset: Long = 0L,
        length: Long = 256L * 1024,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): RootfsTextSlice {
        val location = resolveRootfsPath(root, path, includeAndroidLocal, extraBindMounts)
        return fileSystem.readTextRange(location.rootDir, location.relativePath, offset, length)
    }

    fun rootfsFileSize(
        root: String,
        path: String,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): Long =
        resolveRootfsFile(root, path, includeAndroidLocal, extraBindMounts)
            .also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(
        root: String,
        path: String,
        outputStream: OutputStream,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ) {
        val file = resolveRootfsFile(root, path, includeAndroidLocal, extraBindMounts)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 按 Rootfs 内绝对路径写入 UTF-8 文本, 与 [resolveRootfsFile] 路径解析对称。
     *
     * 直接通过 [resolveRootfsPath] 映射到宿主机物理路径后用 Java IO 写入, 不经过 PRoot,
     * 因此 /sdcard(手机外部存储) 这类 FUSE 挂载点也能可靠读写, 不受 PRoot bind mount 限制。
     */
    fun writeRootfsText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceFileEntry =
        writeRootfsBytes(root, path, text.toByteArray(Charsets.UTF_8), overwrite, includeAndroidLocal, extraBindMounts)

    /** 与 [writeRootfsText] 对称的二进制写入, 用于导入离线安装包等场景 */
    fun writeRootfsBytes(
        root: String,
        path: String,
        bytes: ByteArray,
        overwrite: Boolean = true,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceFileEntry {
        val location = resolveRootfsPath(root, path, includeAndroidLocal, extraBindMounts)
        val file = fileSystem.resolve(location.rootDir, location.relativePath)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return WorkspaceFileEntry(
            path = path.trimEnd('/'),
            name = file.name,
            isDirectory = false,
            sizeBytes = file.length(),
            updatedAt = file.lastModified(),
        )
    }

    private fun resolveRootfsFile(
        root: String,
        path: String,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): File {
        val location = resolveRootfsPath(root, path, includeAndroidLocal, extraBindMounts)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        includeAndroidLocal: Boolean = true,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
        shellCompatibilityMode: Boolean = false,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        // Android 本地互通关闭时, 不再把 /skills、/tool_outputs、/upload、/sdcard 等挂进 Rootfs
        val effectiveBindMounts = if (includeAndroidLocal) bindMounts else emptyList()
        // 确保 rootfs 内 /sdcard 占位目录可写(proot 绑定定位需要); 部分挂载时写入告示
        val sdcardTarget = extraBindMounts
            .firstOrNull { it.target.trimEnd('/').startsWith("/sdcard/") }
            ?.target?.trimEnd('/')
        if (sdcardTarget != null) {
            // /sdcard 部分挂载: shell 命令文本层的确定性范围拦截(权限位在伪 root 下无效)
            ensureShellCommandSdcardScope(command, sdcardTarget)
        }
        ensureSdcardPlaceholderDir(linuxDir(root), partialSdcardMount = sdcardTarget != null)
        var result = shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = effectiveBindMounts,
                extraBindMounts = extraBindMounts,
                sdcardMountTarget = sdcardTarget,
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )
        // 事后兜底: 清理穿越进占位目录的文件, 并把清理结果回告 AI(静默失效 -> 显式反馈)
        if (sdcardTarget != null) {
            val leaked = cleanupSdcardPlaceholder(linuxDir(root), sdcardTarget)
            if (leaked.isNotEmpty()) {
                val warning = leaked.joinToString("\n") {
                    "[工作区] 已清理写入到未挂载占位目录的文件: $it (它不会出现在手机上; 手机文件夹请使用 $sdcardTarget)。" +
                        "注意: 若该操作是从挂载目录 mv/cp 出来的, 原件已随本次清理删除且不可恢复——请直接在 $sdcardTarget 内操作"
                }
                result = result.copy(stderr = result.stderr.trimEnd('\n') + "\n" + warning + "\n")
            }
        }
        return result
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 用户本地目录镜像的挂载点（/local -> 手机本地目录） */
        /** 已下线的 /local 通道(仅用于显式拒绝, 见 resolveRootfsPath) */
        private const val REMOVED_LOCAL_DIR = "/local"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
