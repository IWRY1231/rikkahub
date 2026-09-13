package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 * 同时读取 ~/.agents、/workspace 和会话当前目录中的 AGENTS.md 作为工作区指令。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatToolFactory.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd, workspaceRepository.allFilesAccessGranted()) +
            buildAgentsPrompt(workspaceId, ctx.workspaceCwd)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    private suspend fun buildAgentsPrompt(workspaceId: String, cwd: String?): String {
        // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按 /workspace 解析。
        val workingDirectory = Paths.get("/workspace")
            .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "/root/.agents/AGENTS.md",
            "/workspace/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        val instructions = paths.mapNotNull { path ->
            try {
                val size = workspaceRepository.rootfsFileSize(workspaceId, path)
                require(size <= MAX_AGENTS_BYTES) { "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes" }
                val content = ByteArrayOutputStream().use { output ->
                    workspaceRepository.exportRootfsFile(workspaceId, path, output)
                    output.toString(Charsets.UTF_8.name())
                }
                content.takeIf { it.isNotBlank() }?.let { path to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("WorkspaceReminder", "Skipping workspace instructions: $path", e)
                null
            }
        }
        if (instructions.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            appendLine("<workspace_instructions>")
            appendLine("Follow the AGENTS.md instructions below.")
            instructions.forEach { (path, content) ->
                appendLine()
                appendLine("AGENTS.md source: $path")
                appendLine(content)
            }
            append("</workspace_instructions>")
        }
    }

    private companion object {
        const val MAX_AGENTS_BYTES = 64L * 1024
    }
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    sdcardGranted: Boolean = true,
): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
    // Android 本地互通恒开启（原总开关已停用），但 /sdcard 是否真的可用取决于「所有文件访问」权限:
    // 提示词必须**陈述事实**（未授权时明确说不可用）, 否则模型会把"空目录"解释成"手机里没有文件"
    if (!sdcardGranted) {
        appendLine("- Phone storage (`/sdcard`) is NOT available in this session: the \u300cAll files access\u300d permission has NOT been granted, so nothing from the phone is mounted into the workspace. Do NOT try to read or write `/sdcard` — such calls fail immediately. If the user asks to work with phone files, tell them to grant \u300c所有文件访问\u300d on the workspace detail page (and restart the app as prompted) first.")
    } else if (workspace.sdcardSubPath.isNullOrBlank()) {
        appendLine("- Your phone's storage is mounted at `/sdcard` (full storage access is granted). Read or write user files directly under `/sdcard/<path>`.")
    } else {
        val parent = workspace.sdcardSubPath.substringBeforeLast('/', "")
        val parentHint = if (parent.isNotEmpty()) " e.g. `/sdcard/$parent`," else ""
        appendLine("- The phone folder `/sdcard/${workspace.sdcardSubPath}` is mounted at the same path `/sdcard/${workspace.sdcardSubPath}` (direct access, no sync). Only this folder is available.")
        appendLine("  - IMPORTANT: any other path under `/sdcard` — including its parent/middle directories ($parentHint) — is a sandbox placeholder, NOT the phone storage: listing it gives an incomplete view and references to it are rejected before execution. Never read or write outside `/sdcard/${workspace.sdcardSubPath}`; if the user needs a broader scope, ask them to change \u300c挂载子目录\u300d in the workspace settings.")
    }
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
