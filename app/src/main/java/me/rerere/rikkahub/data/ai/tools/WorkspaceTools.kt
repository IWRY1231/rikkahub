package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.io.File

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

/** 文档(PDF/DOCX/PPTX/EPUB)提取文本的字符数上限, 防止超大文档撑爆上下文 */
private const val MAX_DOCUMENT_CHARS = 200_000

/** 走文档解析而非按文本读取的扩展名 */
private val DOCUMENT_EXTENSIONS = setOf("pdf", "docx", "pptx", "epub")

// 默认审批策略(用户 2026-09-13 决定): 只有 shell 默认需审批;
// 读/列目录/写/编辑/删除/移动全部默认免审批(白名单外路径仍强制审批, 见 WRITABLE_ROOT_PREFIXES)。
// 每个工作区可在详情页「工具审批」里逐个覆盖(覆盖值优先于此处默认)。
val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_list_files" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_delete_file" to false,
    "workspace_move_file" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createListFilesTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createDeleteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createMoveFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Phone storage is mounted at /sdcard when granted.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
        For large text files pass offset/limit to read a byte range instead of the whole file.
        PDF/DOCX/PPTX/EPUB files are parsed to text automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Byte offset to start reading from. Defaults to 0. Use with limit to read large files in slices."
                    )
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Max bytes to read. Defaults to the whole file (capped at " +
                            "${MAX_READ_FILE_BYTES / 1024 / 1024}MB)."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        when {
            path.isImagePath() -> workspaceRepository.readImageInRootfs(workspaceId, path)

            path.isDocumentPath() -> {
                val text = workspaceRepository.readDocumentTextInRootfs(workspaceId, path)
                listOf(UIMessagePart.Text(textPayload(path, text)))
            }

            else -> {
                val offset = params.string("offset")?.toLongOrNull()?.coerceAtLeast(0L)
                val limit = params.string("limit")?.toLongOrNull()?.coerceIn(1L, MAX_READ_FILE_BYTES)
                if (offset != null || limit != null) {
                    // 分段读取: 大文件不再受整文件大小限制
                    val slice = workspaceRepository.readTextRangeInRootfs(
                        workspaceId, path, offset ?: 0L, limit ?: MAX_READ_FILE_BYTES,
                    )
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("path", path)
                                put("text", slice.text)
                                put("offset", slice.start)
                                put("totalBytes", slice.totalBytes)
                                if (slice.truncated) {
                                    put("truncated", true)
                                    put("nextOffset", slice.nextOffset)
                                }
                            }.toString()
                        )
                    )
                } else {
                    val text = workspaceRepository.readTextInRootfs(workspaceId, path)
                    listOf(UIMessagePart.Text(textPayload(path, text)))
                }
            }
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Phone storage is mounted at /sdcard when granted.
        Content is UTF-8 text by default; pass encoding="base64" to write binary data (e.g. images, archives, fonts).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
                put("encoding", buildJsonObject {
                    put("type", "string")
                    put("description", "Content encoding: \"utf8\" (default) or \"base64\" for binary data.")
                    put("enum", buildJsonArray {
                        add("utf8")
                        add("base64")
                    })
                })
            },
            required = listOf("path", "text"),
        )
    },
    // 二进制(base64)写入与文本同等对待(用户 2026-09-13 决定): 仅白名单外路径强制审批
    needsApproval = {
        needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path")
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val encoding = params.string("encoding")?.trim()?.lowercase() ?: "utf8"
        val entry = when (encoding) {
            "utf8", "" -> workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
            "base64" -> {
                val bytes = runCatching { android.util.Base64.decode(text.trim(), android.util.Base64.DEFAULT) }
                    .getOrElse { error("text is not valid base64: ${it.message}") }
                workspaceRepository.writeBytesInRootfs(workspaceId, path, bytes, overwrite)
            }
            else -> error("unsupported encoding: $encoding (use \"utf8\" or \"base64\")")
        }
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Phone storage is mounted at /sdcard when granted.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createListFilesTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_files",
    description = """
        List a directory inside the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area. Phone storage is mounted at /sdcard when granted.
        Returns each entry's path, type (file/directory), size and modified time.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_list_files") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        val entries = workspaceRepository.listInRootfs(workspaceId, path)
        val base = path.trimEnd('/')
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("count", entries.size)
                    put("entries", buildJsonArray {
                        entries.forEach { entry ->
                            add(buildJsonObject {
                                put("path", if (base.isEmpty()) "/${entry.name}" else "$base/${entry.name}")
                                put("type", if (entry.isDirectory) "directory" else "file")
                                put("sizeBytes", entry.sizeBytes)
                                put("updatedAt", entry.updatedAt)
                            })
                        }
                    })
                }.toString()
            )
        )
    },
)

private fun createDeleteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_delete_file",
    description = """
        Delete a file or directory inside the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Deleting a non-empty directory requires recursive=true. Deletion is permanent, there is no trash.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Required to delete a non-empty directory. Defaults to false.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_delete_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val recursive = params["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val deleted = workspaceRepository.deleteInRootfs(workspaceId, path, recursive)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("deleted", deleted)
                }.toString()
            )
        )
    },
)

private fun createMoveFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_move_file",
    description = """
        Move or rename a file/directory inside the assistant's bound workspace Rootfs. Both paths must be absolute inside Rootfs.
        Pass overwrite=true to replace an existing target. Moving a directory across mounts
        (e.g. /sdcard -> /workspace) is not supported, use workspace_shell with mv instead.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute source path inside Rootfs")
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute target path inside Rootfs")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Overwrite an existing target. Defaults to false.")
                })
            },
            required = listOf("source", "target"),
        )
    },
    needsApproval = {
        needsApproval("workspace_move_file") ||
            it.pathOutsideWritableRoots("source") || it.pathOutsideWritableRoots("target")
    },
    execute = {
        val params = it.jsonObject
        val source = params.absolutePath("source")
        val target = params.absolutePath("target")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val entry = workspaceRepository.moveInRootfs(workspaceId, source, target, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Phone storage is mounted at /sdcard when granted. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun String.isDocumentPath(): Boolean =
    substringAfterLast('.', "").lowercase() in DOCUMENT_EXTENSIONS

/** read_file 的文本载荷(带字符数, 便于模型判断是否需要分段) */
private fun textPayload(path: String, text: String): String =
    buildJsonObject {
        put("path", path)
        put("text", text)
        put("chars", text.length)
    }.toString()

/** 读取 PDF/DOCX/PPTX/EPUB: 先导出到缓存文件, 用文档解析器提取文本后删除临时文件 */
private suspend fun WorkspaceRepository.readDocumentTextInRootfs(
    workspaceId: String,
    path: String,
): String {
    val context: Context = getKoin().get()
    val extension = path.substringAfterLast('.', "").lowercase()
    val temp = File.createTempFile("workspace-document-", ".$extension", context.cacheDir)
    return try {
        temp.outputStream().use { exportRootfsFile(workspaceId, path, it) }
        val text = when (extension) {
            "pdf" -> PdfParser.parserPdf(temp)
            "docx" -> DocxParser.parse(temp)
            "pptx" -> PptxParser.parse(temp)
            "epub" -> EpubParser.parse(temp)
            else -> error("Unsupported document type: $path")
        }
        if (text.length > MAX_DOCUMENT_CHARS) {
            text.take(MAX_DOCUMENT_CHARS) +
                "\n...(truncated, ${text.length - MAX_DOCUMENT_CHARS} more chars read via shell if needed)"
        } else {
            text
        }
    } finally {
        temp.delete()
    }
}

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录、临时目录 /tmp、技能目录, 以及已授权本地访问后的手机存储 /sdcard
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp", "/sdcard", "/skills")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
