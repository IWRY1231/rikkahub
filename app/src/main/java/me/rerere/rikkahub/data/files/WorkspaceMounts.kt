package me.rerere.rikkahub.data.files

import android.content.Context
import android.os.Environment
import android.util.Log
import me.rerere.workspace.WorkspaceBindMount
import java.io.File

/**
 * Android 本地目录挂载表。
 *
 * 同一份挂载表同时用于:
 * - PRoot shell 的 -b 参数（AI 命令执行与交互式终端），见 ProotShellRunner.buildBindMountArgs;
 * - 文件工具的路径解析（WorkspaceManager.resolveRootfsPath）。
 * 避免两处挂载点漂移。
 *
 * 手机存储(/sdcard)不再默认整盘挂载: 由用户在每个工作区配置挂载子目录
 * （见 [sdcardMount]），以直连方式(零拷贝)挂进容器，需要「所有文件访问」权限。
 */
object WorkspaceMounts {
    private const val TAG = "WorkspaceMounts"

    fun androidLocalMounts(context: Context): List<WorkspaceBindMount> = buildList {
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }, "/skills"))
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }, "/tool_outputs"))
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }, "/upload"))
        // /sdcard 不在此处静态挂载: 按工作区配置的子目录动态附加, 见 [sdcardMount]
    }

    /**
     * 按用户配置的子目录生成 /sdcard 挂载（直连真实路径, 无需镜像同步）:
     * - [subPath] 为空/空白 → 挂载整个 /sdcard;
     * - "Download"        → 挂载 /sdcard/Download 为容器内 /sdcard/Download;
     * - "DCIM/Camera"     → 挂载 /sdcard/DCIM/Camera 为容器内 /sdcard/DCIM/Camera。
     *
     * 含 "." / ".." 等路径穿越段时返回 null（拒绝挂载）。
     */
    fun sdcardMount(subPath: String?): WorkspaceBindMount? {
        val sdcard = Environment.getExternalStorageDirectory() ?: return null
        val segments = subPath?.trim()?.trim('/')
            ?.split('/')?.filter { it.isNotBlank() }
            ?: emptyList()
        if (segments.isEmpty()) {
            return WorkspaceBindMount(sdcard, "/sdcard")
        }
        if (segments.any { it == "." || it == ".." }) {
            Log.w(TAG, "拒绝非法挂载子目录: $subPath")
            return null
        }
        val joined = segments.joinToString("/")
        return WorkspaceBindMount(File(sdcard, joined), "/sdcard/$joined")
    }
}
