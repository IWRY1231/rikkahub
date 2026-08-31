package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

/** 把挂载表转换为 PRoot 的 -b 参数序列，AI 命令执行与交互式终端共用同一份逻辑 */
fun buildBindMountArgs(bindMounts: List<WorkspaceBindMount>): List<String> =
    bindMounts.filter { it.source.exists() }
        .flatMap { listOf("-b", "${it.source.absolutePath}:${it.target.trimEnd('/')}") }

/**
 * 生成 AI 命令的 shell 包装串。
 *
 * [partialSdcardMount] 为 true(/sdcard 部分挂载)时:
 * - 命令结束后取物理 pwd, 若落在 /sdcard 占位子树(且不在已挂载目标内),
 *   向 stderr 注入警告——防止 `cd .. && ls` 之类只读穿越让 AI 误以为看到的是手机内容;
 * - rc 全程透传, 不影响命令退出码。
 */
internal fun buildShellWrapper(partialSdcardMount: Boolean, allowedTarget: String): String {
    val base = "cd -- \"\$1\" && eval \"\$2\""
    if (!partialSdcardMount) return base
    val allowed = allowedTarget.trimEnd('/')
    return base +
        "; rc=\$?; __p=\$(pwd -P 2>/dev/null || pwd); " +
        "case \"\$__p\" in \"$allowed\"|\"$allowed\"/*) : ;; " +
        "\"/sdcard\"|\"/sdcard\"/*) printf '%s\\n' \"[工作区] 警告: 当前目录 \$__p 是沙盒占位而非手机存储, 其内容不代表手机真实文件; 手机文件夹请使用 $allowed\" >&2 ;; " +
        "esac; exit \$rc"
}

/**
 * 命令执行后的兜底清理: 扫描 rootfs 内 /sdcard 占位树, 把"穿越进占位目录"的文件
 * (cd 后相对路径写入、绕过文本检查的间接写法等)删除, 并返回其容器内路径清单。
 * 白名单: MOUNT_NOTICE.txt 与挂载目标路径本身(proot 绑定定位所建)。
 * 这些文件本来就到不了手机, 清理只是让"静默失效"变成"显式反馈"。
 */
internal fun cleanupSdcardPlaceholder(linuxDir: File, sdcardTarget: String): List<String> {
    val root = File(linuxDir, "sdcard")
    if (!root.isDirectory) return emptyList()
    val allowedSegments = sdcardTarget.trim('/').split('/').filter { it.isNotBlank() }
    val leaked = mutableListOf<String>()
    fun scan(dir: File, depth: Int, guestPrefix: String) {
        dir.listFiles()?.forEach { child ->
            val guestPath = "$guestPrefix/${child.name}"
            val allowedHere = depth < allowedSegments.size &&
                child.name == allowedSegments[depth] &&
                child.isDirectory
            when {
                child.isFile && child.name == "MOUNT_NOTICE.txt" -> Unit // 告示
                allowedHere -> scan(child, depth + 1, guestPath)
                else -> {
                    leaked.add(if (child.isDirectory) "$guestPath/" else guestPath)
                    child.deleteRecursively()
                }
            }
        }
    }
    scan(root, 0, "/sdcard")
    return leaked
}

/**
 * 确保 rootfs 内 /sdcard 占位目录存在且可写——proot 启动时需要在该目录下定位/创建
 * 绑定目标(如 /sdcard/Download/Agent), 因此它必须保持为可写目录。
 *
 * 部分挂载模式下额外写入 MOUNT_NOTICE.txt, 供 `ls /sdcard` 时识别占位身份。
 * 注意: proot --root-id 的伪 root 会使权限位失效(实测 chmod 555 无法阻止写入),
 * 因此范围外的硬拦截不在文件系统层, 而在 shell 命令文本层
 * (见 [ensureShellCommandSdcardScope])。
 */
fun ensureSdcardPlaceholderDir(linuxDir: File, partialSdcardMount: Boolean) {
    runCatching {
        val dir = File(linuxDir, "sdcard")
        if (dir.isFile) dir.delete()
        if (!dir.isDirectory) dir.mkdirs()
        dir.setWritable(true, false)
        val notice = File(dir, "MOUNT_NOTICE.txt")
        if (partialSdcardMount) {
            val text = "此目录是工作区沙盒占位, 不是手机存储!\n" +
                "当前仅挂载了: /sdcard 下的用户指定子目录, 范围外路径的读写会被拒绝。\n" +
                "This is a sandbox placeholder; only the user-selected subdirectory of /sdcard is mounted.\n"
            if (!notice.isFile || notice.readText() != text) notice.writeText(text)
        } else {
            if (notice.exists()) notice.delete()
        }
    }
}

/**
 * /sdcard 部分挂载模式下, 对即将执行的 shell 命令做路径范围检查(确定性的入口拦截):
 * 命令文本中出现的 /sdcard 引用只允许恰好是 [allowedTarget] 及其子路径
 * (裸 /sdcard 允许——那是带 MOUNT_NOTICE 的占位视图, ls 可见)。
 *
 * 处理细节:
 * - 前一个字符须为分隔符, 避免 /usr/share/sdcard-doc 之类误伤;
 * - 命令中出现的 .. 段会先做规范化, /sdcard/../etc 一样被拒绝;
 * - 兄弟前缀(如挂载 /sdcard/Download/Agent 时引用 /sdcard/Download)同样拒绝。
 *
 * 已知局限: cd + 相对路径、变量间接引用等写法无法从文本层完全覆盖,
 * 该检查与提示词警告、占位告示共同构成纵深防御; 文件工具则是完全确定性的。
 */
fun ensureShellCommandSdcardScope(command: String, allowedTarget: String) {
    val allowed = allowedTarget.trimEnd('/')
    var idx = command.indexOf("/sdcard")
    while (idx >= 0) {
        val prev = if (idx == 0) ' ' else command[idx - 1]
        val prevIsDelimiter = !prev.isLetterOrDigit() && prev !in setOf('_')
        if (prevIsDelimiter) {
            var end = idx + "/sdcard".length
            while (end < command.length && (command[end].isLetterOrDigit() || command[end] in "/._-+={}\$@%")) end++
            val token = command.substring(idx, end).trimEnd(',', ';', ':', '!', '?')
            if (token == "/sdcard" || token.startsWith("/sdcard/")) {
                val normalized = normalizeSdcardPath(token)
                val passesThroughAllowed = token == allowed || token.startsWith("$allowed/")
                val withinAllowed = normalized == allowed || normalized.startsWith("$allowed/")
                if (!withinAllowed && !(normalized == "/sdcard" && !passesThroughAllowed)) {
                    error(
                        "shell 命令引用了未挂载的路径 \"$token\"(部分挂载模式, 仅 $allowed 可用); " +
                            "已拒绝执行。请只在 $allowed 内操作。"
                    )
                }
            }
        }
        idx = command.indexOf("/sdcard", idx + 1)
    }
}

/** 规范化路径: 过滤空段与 ".", 弹出 ".."(弹出空则视为根) */
private fun normalizeSdcardPath(token: String): String {
    val out = ArrayDeque<String>()
    for (seg in token.split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> out.removeLastOrNull()
            else -> out.addLast(seg)
        }
    }
    return "/" + out.joinToString("/")
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        command += buildBindMountArgs(context.bindMounts)
        command += buildBindMountArgs(context.extraBindMounts)

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            "/bin/bash",
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            // /sdcard 部分挂载时, 包装器在命令结束后检查 pwd 并对占位子树注入警告
            buildShellWrapper(
                partialSdcardMount = context.sdcardMountTarget != null,
                allowedTarget = context.sdcardMountTarget ?: "/sdcard",
            ),
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}
