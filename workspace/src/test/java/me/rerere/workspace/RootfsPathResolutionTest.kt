package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class RootfsPathResolutionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var manager: WorkspaceManager

    private val root = "test-workspace"

    private fun createManager(): WorkspaceManager {
        skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        return WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills"),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        ).also { it.ensureWorkspace(root) }
    }

    @Test
    fun readsFileWrittenThroughBindMountPath() {
        manager = createManager()
        File(skillsDir, "issue-1561").mkdirs()
        File(skillsDir, "issue-1561/SKILL.md").writeText("---\nversion: before\n---\n")

        val size = manager.rootfsFileSize(root, "/skills/issue-1561/SKILL.md")
        val buffer = ByteArrayOutputStream(size.toInt())
        manager.exportRootfsFile(root, "/skills/issue-1561/SKILL.md", buffer)

        assertEquals("---\nversion: before\n---\n", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun writesFileThroughBindMountPath() {
        manager = createManager()
        File(skillsDir, "issue-1561").mkdirs()

        val entry = manager.writeRootfsText(root, "/skills/issue-1561/out.txt", "hello bind")

        assertEquals("/skills/issue-1561/out.txt", entry.path)
        assertEquals("out.txt", entry.name)
        assertEquals("hello bind".toByteArray().size.toLong(), entry.sizeBytes)
        assertEquals("hello bind", File(skillsDir, "issue-1561/out.txt").readText())
    }

    @Test
    fun androidLocalMountIsNotResolvedWhenInteropDisabled() {
        manager = createManager()
        File(skillsDir, "issue-1561").mkdirs()
        File(skillsDir, "issue-1561/SKILL.md").writeText("hidden")

        // 互通关闭后, /skills 不再是 Android 本地目录的解析目标, 回落到 Rootfs 内部
        val location = manager.resolveRootfsPath(root, "/skills/issue-1561/SKILL.md", includeAndroidLocal = false)
        assertEquals(manager.linuxDir(root), location.rootDir)
        assertEquals("skills/issue-1561/SKILL.md", location.relativePath)
    }

    @Test
    fun workspaceAreaStillResolvesWhenInteropDisabled() {
        manager = createManager()
        File(manager.filesDir(root), "notes.txt").writeText("hello")

        val location = manager.resolveRootfsPath(root, "/workspace/notes.txt", includeAndroidLocal = false)
        assertEquals(manager.filesDir(root), location.rootDir)
        assertEquals("notes.txt", location.relativePath)
    }

    @Test
    fun commandExecutionOmitsAndroidLocalMountsWhenInteropDisabled() {
        var captured: List<WorkspaceBindMount>? = null
        val recordingRunner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                captured = context.bindMounts
                return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
            }
        }
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = tempFolder.newFolder("skills"), target = "/skills"),
                WorkspaceBindMount(source = tempFolder.newFolder("upload"), target = "/upload"),
            ),
            shellRunner = recordingRunner,
        ).also { it.ensureWorkspace(root) }

        manager.executeCommand(root, "ls", includeAndroidLocal = false)
        assertTrue(captured.orEmpty().isEmpty())

        manager.executeCommand(root, "ls", includeAndroidLocal = true)
        assertEquals(2, captured?.size ?: 0)
    }

    @Test
    fun bindMountTargetDoesNotMatchLongerSiblingPrefix() {
        val skills = tempFolder.newFolder("skills-src")
        val skillsets = tempFolder.newFolder("skillsets-src")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skills, target = "/skills"),
                WorkspaceBindMount(source = skillsets, target = "/skillsets"),
            ),
        ).also { it.ensureWorkspace(root) }

        assertEquals(skills, manager.resolveRootfsPath(root, "/skills/a.md").rootDir)
        assertEquals(skillsets, manager.resolveRootfsPath(root, "/skillsets/a.md").rootDir)
    }

    @Test
    fun workspacePathStillResolvesToFilesArea() {
        manager = createManager()
        File(manager.filesDir(root), "notes.txt").writeText("hello")

        val location = manager.resolveRootfsPath(root, "/workspace/notes.txt")
        assertEquals(manager.filesDir(root), location.rootDir)
        assertEquals("notes.txt", location.relativePath)

        val buffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/workspace/notes.txt", buffer)
        assertEquals("hello", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun sdcardOutsideSubMountIsRejectedLoudly() {
        val sdcardDir = tempFolder.newFolder("fake-sdcard")
        File(sdcardDir, "Download").mkdirs()
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = emptyList(),
        ).also { it.ensureWorkspace(root) }

        val mounts = listOf(WorkspaceBindMount(source = File(sdcardDir, "Download"), target = "/sdcard/Download"))

        // 挂载范围内正常解析
        assertEquals(
            File(sdcardDir, "Download/a.txt"),
            manager.resolveRootfsPath(root, "/sdcard/Download/a.txt", extraBindMounts = mounts).rootDir,
        )
        // 挂载范围外: 显式报错而不是静默落进沙盒占位目录
        val error = assertThrows(IllegalStateException::class.java) {
            manager.resolveRootfsPath(root, "/sdcard/DCIM/x.jpg", extraBindMounts = mounts)
        }
        assertTrue(error.message!!.contains("部分挂载"))

        // 完全未挂载 /sdcard 时同样显式报错
        val error2 = assertThrows(IllegalStateException::class.java) {
            manager.resolveRootfsPath(root, "/sdcard/DCIM/x.jpg")
        }
        assertTrue(error2.message!!.contains("/sdcard"))
    }

    @Test
    fun sdcardPlaceholderDirGetsNoticeAndStaysWritable() {
        val linuxDir = tempFolder.newFolder("linux")
        val sdcardDir = File(linuxDir, "sdcard")

        // 部分挂载: 占位目录存在、可写(proot 绑定定位需要), 并带有 MOUNT_NOTICE 告示
        ensureSdcardPlaceholderDir(linuxDir, partialSdcardMount = true)
        assertTrue(sdcardDir.isDirectory)
        assertTrue(sdcardDir.canWrite())
        val notice = File(sdcardDir, "MOUNT_NOTICE.txt")
        assertTrue(notice.isFile)
        assertTrue(notice.readText().contains("Permission denied"))

        // 整盘/未启用: 告示移除
        ensureSdcardPlaceholderDir(linuxDir, partialSdcardMount = false)
        assertTrue(sdcardDir.isDirectory)
        assertTrue(!notice.exists())
    }

    @Test
    fun shellWrapperAddsSdcardReadOnlyGuardOnlyInPartialMode() {
        val plain = buildShellWrapper(sdcardPartialGuard = false)
        assertTrue(plain.contains("eval") && !plain.contains("chmod"))

        val guarded = buildShellWrapper(sdcardPartialGuard = true)
        assertTrue(guarded.contains("chmod 555 /sdcard"))
        assertTrue(guarded.contains("eval"))
        // 命令执行后必须恢复可写, 保证下一次 proot 绑定定位正常
        assertTrue(guarded.contains("chmod 755 /sdcard"))
        assertTrue(guarded.indexOf("chmod 555") < guarded.indexOf("eval"))
        assertTrue(guarded.indexOf("eval") < guarded.indexOf("chmod 755"))
    }

    @Test
    fun unknownAbsolutePathFallsBackToRootfsInterior() {
        manager = createManager()
        File(manager.linuxDir(root), "etc").mkdirs()
        File(manager.linuxDir(root), "etc/hostname").writeText("rikkahub\n")

        val buffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/etc/hostname", buffer)
        assertEquals("rikkahub\n", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun traversalOutOfBindMountIsRejected() {
        manager = createManager()
        tempFolder.newFile("secret.txt").writeText("secret")

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/../secret.txt")
        }
        assertTrue(error.message!!.contains("escapes workspace root"))
    }

    @Test
    fun kernelFilesystemPathIsRejectedWithHint() {
        manager = createManager()

        val error = assertThrows(IllegalStateException::class.java) {
            manager.rootfsFileSize(root, "/proc/version")
        }
        assertTrue(error.message!!.contains("workspace_shell"))
    }

    @Test
    fun missingFileReportsOriginalAbsolutePath() {
        manager = createManager()

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/missing/SKILL.md")
        }
        assertEquals("File does not exist: /skills/missing/SKILL.md", error.message)
    }

    @Test
    fun directoryPathIsNotReadableAsFile() {
        manager = createManager()
        File(skillsDir, "issue-1561").mkdirs()

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/issue-1561")
        }
        assertEquals("Path is not a file: /skills/issue-1561", error.message)
    }
}
