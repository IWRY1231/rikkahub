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
 * 确保 rootfs 内 /sdcard 占位目录存在且可写——proot 启动时需要在该目录下定位/创建
 * 绑定目标(如 /sdcard/Download/Agent), 因此它必须保持为可写目录。
 *
 * 部分挂载模式下额外写入 MOUNT_NOTICE.txt, 供 `ls /sdcard` 时识别占位身份;
 * 对范围外路径的硬拦截由 shell 包装层完成(见 [buildShellWrapper])。
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
                "当前仅挂载了: /sdcard 下的用户指定子目录, 范围外路径的读写会被拒绝(Permission denied)。\n" +
                "This is a sandbox placeholder; only the user-selected subdirectory of /sdcard is mounted.\n"
            if (!notice.isFile || notice.readText() != text) notice.writeText(text)
        } else {
            if (notice.exists()) notice.delete()
        }
    }
}

/**
 * 生成 AI 命令的 shell 包装串。
 *
 * [sdcardPartialGuard] 为 true(/sdcard 部分挂载)时, 在用户命令执行前后分别把容器内
 * /sdcard 切为只读/恢复: proot 已在启动阶段完成绑定定位, 执行期 /sdcard 变只读不会
 * 影响已绑定子目录的读写(它们经路径翻译直连真实路径), 但会让范围外的任何
 * 创建/写入立即得到 Permission denied, 从根上杜绝"静默写进沙盒占位目录"。
 */
internal fun buildShellWrapper(sdcardPartialGuard: Boolean): String =
    if (sdcardPartialGuard) {
        "set -f && cd -- \"\$1\" && chmod 555 /sdcard 2>/dev/null; eval \"\$2\"; rc=\$?; " +
            "chmod 755 /sdcard 2>/dev/null; exit \$rc"
    } else {
        "set -f && cd -- \"\$1\" && eval \"\$2\""
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
            // /sdcard 部分挂载时由包装器在命令前后切换 /sdcard 只读(硬拦截范围外写入)
            buildShellWrapper(context.sdcardPartialGuard),
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
