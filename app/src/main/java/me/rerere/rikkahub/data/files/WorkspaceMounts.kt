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
 * /sdcard 需要在系统设置授予「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 权限后才能读写,
 * 未授权时目录内容为空, 功能不受影响。
 */
object WorkspaceMounts {
    private const val TAG = "WorkspaceMounts"

    fun androidLocalMounts(context: Context): List<WorkspaceBindMount> = buildList {
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }, "/skills"))
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }, "/tool_outputs"))
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }, "/upload"))
        val sdcard = Environment.getExternalStorageDirectory()
        if (sdcard != null) {
            add(WorkspaceBindMount(sdcard, "/sdcard"))
        } else {
            Log.w(TAG, "External storage not available, skipping /sdcard mount")
        }
    }
}
