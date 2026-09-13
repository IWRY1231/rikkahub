package me.rerere.rikkahub.data.files

import android.content.Context
import android.os.Environment
import android.util.Log
import me.rerere.rikkahub.utils.hasAllFilesAccessPermission
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

    /**
     * 「所有文件访问」权限未授予时的统一说明。
     *
     * 单一事实源, 三处共用: ① 文件工具的路径预检 ② AI shell 命令的文本预检（[commandReferencesSdcardPath]）
     * ③ 交互式终端的 MOUNT_NOTICE 告示。原则: 未授权的能力要**响亮报错**, 不允许静默退化成"空目录"。
     */
    const val SDCARD_PERMISSION_REQUIRED_MESSAGE: String =
        "「所有文件访问」权限未授予: 手机存储(/sdcard)没有挂载到工作区, 当前无法读写。" +
            "请在「工作区详情页 → 所有文件访问」授权(授予后按提示重启应用)再重试。"

    /**
     * 是否已授予「所有文件访问」（/sdcard 可用性的单一事实源）。
     *
     * Android 11+ 走 MANAGE_EXTERNAL_STORAGE; 更低版本回退 READ_EXTERNAL_STORAGE(见 utils/ContextUtil.kt)。
     */
    fun allFilesAccessGranted(context: Context): Boolean = context.hasAllFilesAccessPermission()

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
