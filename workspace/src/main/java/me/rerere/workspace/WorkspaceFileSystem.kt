package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    /**
     * 读取文件指定的字节区间内容, 返回文本与区间信息。
     *
     * 与 [readText] 不同: 这里**不套用 maxReadBytes**(由调用方决定分片大小), 用于大文件分段读取。
     * 分片边界会**对齐到字符**(UTF-8): 返回的 [RootfsTextSlice.start]/[RootfsTextSlice.end] 是实际生效区间,
     * 按 [RootfsTextSlice.nextOffset] 续读既不丢字也不产生乱码; 实际区间可能比 length 略大(最多 4 字节)。
     */
    fun readTextRange(
        root: File,
        path: String,
        offset: Long = 0L,
        length: Long = DEFAULT_SLICE_BYTES,
        charset: Charset = StandardCharsets.UTF_8,
    ): RootfsTextSlice {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        val total = file.length()
        val rawStart = offset.coerceIn(0L, total)
        // 多读 MAX_UTF8_CHAR_BYTES 字节: 保证窗口内至少有一个完整字符, 按 nextOffset 续读必定推进
        val window = length.coerceAtLeast(0L) + MAX_UTF8_CHAR_BYTES
        val rawEnd = (rawStart + window).coerceIn(rawStart, total)
        if (rawEnd <= rawStart) return RootfsTextSlice("", rawStart, rawStart, total)

        val size = (rawEnd - rawStart).toInt()
        val buffer = ByteArray(size)
        var read = 0
        file.inputStream().use { input ->
            var skipped = 0L
            while (skipped < rawStart) {
                val step = input.skip(rawStart - skipped)
                if (step <= 0L) break
                skipped += step
            }
            while (read < size) {
                val step = input.read(buffer, read, size - read)
                if (step < 0) break
                read += step
            }
        }
        val bytes = if (read == size) buffer else buffer.copyOf(read)

        // 分片边界对齐到字符, 保证按 nextOffset 续读时**不丢字也不产生乱码**:
        // 起点落在续字节上则跳过; 终点落在不完整序列中间则整体留给下一片。
        var leading = 0
        var tailDrop = 0
        if (charset == StandardCharsets.UTF_8) {
            if (rawStart > 0L) {
                while (leading < bytes.size && (bytes[leading].toInt() and 0xC0) == 0x80) leading++
            }
            if (rawStart + read < total) {
                tailDrop = incompleteTrailingBytes(bytes, leading)
            }
        }
        val body = bytes.copyOfRange(leading, bytes.size - tailDrop)
        return RootfsTextSlice(
            text = String(body, charset),
            start = rawStart + leading,
            end = rawStart + read - tailDrop,
            totalBytes = total,
        )
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(root) }
                .toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .forEach { path ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) return@forEach
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size >= config.maxSearchResults) return@useLines
                            if (matcher.containsMatchIn(line)) {
                                results += WorkspaceSearchMatch(
                                    path = file.relativePath(root),
                                    line = index + 1,
                                    text = line,
                                )
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }
}

/** 分段读取时的默认分片大小(256 KiB) */
private const val DEFAULT_SLICE_BYTES = 256L * 1024

/** UTF-8 单字符最大字节数(窗口对齐用) */
private const val MAX_UTF8_CHAR_BYTES = 4L

/**
 * 末尾不完整 UTF-8 序列的字节数(0 表示末尾字符完整), 用于分片边界对齐。
 * 例: 中文 3 字节字符被切成 1~2 字节时返回 1~2, 让下一片从该字符的首字节重新读。
 */
private fun incompleteTrailingBytes(bytes: ByteArray, from: Int): Int {
    var i = bytes.size - 1
    var continuations = 0
    while (i >= from && (bytes[i].toInt() and 0xC0) == 0x80) {
        continuations++
        i--
    }
    if (i < from) return bytes.size - from
    val expected = when (val lead = bytes[i].toInt() and 0xFF) {
        in 0x00..0x7F -> 1
        in 0xC0..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF7 -> 4
        else -> 1
    }
    val seen = continuations + 1
    return if (seen < expected) seen else 0
}
