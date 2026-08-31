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
 * /sdcard 部分挂载模式下的沙盒占位防护。
 *
 * 仅挂载 /sdcard 的某个子目录时, 容器内 /sdcard 的其他路径会回落到 rootfs 的同名占位
 * 目录(proot 自动创建), 对其读写会静默成功但永远到不了手机。这里把占位目录设为只读
 * (r-x) 并写入告示文件, 使 shell 层的误写入立即报 EACCES, 且 ls 即可看到警告。
 *
 * - [extraBindMounts] 含 "/sdcard/xxx" 子目录挂载 → 启用防护(只读+告示);
 * - 不含(整盘挂载或本地互通关闭) → 恢复可写, 不干预。
 */
fun enforceSdcardFallbackGuard(linuxDir: File, extraBindMounts: List<WorkspaceBindMount>) {
    val sdcardRoot = File(linuxDir, "sdcard")
    val subMount = extraBindMounts.firstOrNull { it.target.trimEnd('/').startsWith("/sdcard/") }
    if (subMount == null) {
        // 整盘挂载/未启用: 占位目录即使存在也不影响(bind 完全覆盖), 恢复默认权限
        if (sdcardRoot.isDirectory) runCatching { sdcardRoot.setWritable(true, false) }
        return
    }
    runCatching {
        if (!sdcardRoot.exists()) sdcardRoot.mkdirs()
        val notice = File(sdcardRoot, "MOUNT_NOTICE.txt")
        val expected = "此目录是工作区沙盒占位, 不是手机存储!\n" +
            "当前仅挂载了: ${subMount.target}\n" +
            "对 /sdcard 下其他路径的写入不会出现在手机上(会被拒绝)。\n" +
            "请只使用 ${subMount.target} 。\n" +
            "This is a sandbox placeholder, NOT the phone storage. Only ${subMount.target} is mounted.\n"
        if (!notice.isFile || notice.readText() != expected) notice.writeText(expected)
        sdcardRoot.setWritable(false, false)
    }
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
            "cd -- \"\$1\" && eval \"\$2\"",
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
