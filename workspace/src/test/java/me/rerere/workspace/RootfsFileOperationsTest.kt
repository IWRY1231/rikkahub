package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Rootfs 内绝对路径的文件操作(list/delete/move/分段读取)。
 * 这些能力直接支撑 workspace_list_files / workspace_delete_file / workspace_move_file
 * 与 read_file 的 offset/limit 分段读取。
 */
class RootfsFileOperationsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    private lateinit var skillsDir: File

    private fun createManager(): WorkspaceManager {
        skillsDir = tempFolder.newFolder("skills")
        return WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(WorkspaceBindMount(source = skillsDir, target = "/skills")),
        ).also { it.ensureWorkspace(root) }
    }

    @Test
    fun listsDirectoryThroughRootfsPath() {
        val manager = createManager()
        File(manager.filesDir(root), "src/nested").mkdirs()
        File(manager.filesDir(root), "src/a.txt").writeText("a")
        File(manager.filesDir(root), "src/nested/b.txt").writeText("b")

        val entries = manager.listRootfs(root, "/workspace/src")
        assertEquals(listOf("nested", "a.txt"), entries.map { it.name })
        assertTrue(entries.first { it.name == "nested" }.isDirectory)
        assertFalse(entries.first { it.name == "a.txt" }.isDirectory)
    }

    @Test
    fun deletesFileAndRefusesMountRoot() {
        val manager = createManager()
        File(manager.filesDir(root), "temp.txt").writeText("x")

        assertTrue(manager.deleteRootfs(root, "/workspace/temp.txt"))
        assertFalse(File(manager.filesDir(root), "temp.txt").exists())

        // 拒绝删除挂载点根目录本身
        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.deleteRootfs(root, "/workspace")
        }
        assertTrue(error.message!!.contains("mount root"))
    }

    @Test
    fun deletesDirectoryOnlyWhenRecursive() {
        val manager = createManager()
        File(manager.filesDir(root), "dir/inner.txt").writeText("x")

        assertThrows(IllegalArgumentException::class.java) {
            manager.deleteRootfs(root, "/workspace/dir")
        }
        assertTrue(manager.deleteRootfs(root, "/workspace/dir", recursive = true))
        assertFalse(File(manager.filesDir(root), "dir").exists())
    }

    @Test
    fun movesWithinSameMountAndAcrossMounts() {
        val manager = createManager()
        File(manager.filesDir(root), "old.txt").writeText("content")

        // 同挂载点: rename
        manager.moveRootfs(root, "/workspace/old.txt", "/workspace/new.txt")
        assertFalse(File(manager.filesDir(root), "old.txt").exists())
        assertEquals("content", File(manager.filesDir(root), "new.txt").readText())

        // 跨挂载点(/workspace → /skills): 文件复制后删除源
        manager.moveRootfs(root, "/workspace/new.txt", "/skills/moved.txt")
        assertFalse(File(manager.filesDir(root), "new.txt").exists())
        assertEquals("content", File(skillsDir, "moved.txt").readText())
    }

    @Test
    fun refusesMovingDirectoryAcrossMounts() {
        val manager = createManager()
        File(manager.filesDir(root), "dir/x.txt").writeText("x")

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.moveRootfs(root, "/workspace/dir", "/skills/dir")
        }
        assertTrue(error.message!!.contains("across mounts"))
    }

    @Test
    fun readsLargeFileInSlicesLosslessly() {
        val manager = createManager()
        // 中英混排 + emoji(4 字节) + 制表换行, 确保分片边界经常落在多字节字符中间
        val original = "中文内容🎉abc混合 hello 世界\t\n".repeat(300)
        val file = File(manager.filesDir(root), "big.txt")
        file.writeText(original)
        val total = file.length()

        val collected = StringBuilder()
        var offset = 0L
        var guard = 0
        while (guard++ < 100_000) {
            val slice = manager.readRootfsTextRange(root, "/workspace/big.txt", offset, 5)
            assertEquals(total, slice.totalBytes)
            collected.append(slice.text)
            assertTrue("下一次读取必须推进", slice.end > offset)
            if (!slice.truncated) break
            offset = slice.nextOffset
        }
        assertEquals(original, collected.toString())
    }

    @Test
    fun readsSliceBeyondEofSafely() {
        val manager = createManager()
        File(manager.filesDir(root), "small.txt").writeText("hello")
        val slice = manager.readRootfsTextRange(root, "/workspace/small.txt", 9999, 10)
        assertEquals("", slice.text)
        assertFalse(slice.truncated)
    }
}
