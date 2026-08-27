package com.healthconnect.export.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ExportedFilesCardTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /**
     * Creates [count] temp files with increasing names and modification times
     * (files[0] is the oldest, files[last] is the newest).
     */
    private fun tempFiles(count: Int): List<File> {
        val dir = createTempDirectory("hce-card-test-").toFile()
        tempDirs += dir
        return (0 until count).map { i ->
            val file = File(dir, "health_2026-06-${i + 1}.json")
            file.writeText("{}")
            file.setLastModified(1_700_000_000_000L + i * 1_000L)
            file
        }
    }

    @Test
    fun `collapsed list shows only the newest ten files sorted descending`() {
        val files = tempFiles(12)

        val visible = visibleExportFiles(files, showAll = false)

        assertEquals(10, visible.size)
        assertEquals(files[11].name, visible.first().name)
        assertEquals(files[2].name, visible.last().name)
    }

    @Test
    fun `showAll returns every file sorted descending`() {
        val files = tempFiles(15)

        val visible = visibleExportFiles(files, showAll = true)

        assertEquals(15, visible.size)
        assertEquals(files[14].name, visible.first().name)
        assertEquals(files[0].name, visible.last().name)
    }

    @Test
    fun `lists with ten or fewer files are shown entirely without expanding`() {
        val files = tempFiles(5)

        val visible = visibleExportFiles(files, showAll = false)

        assertEquals(5, visible.size)
    }

    @Test
    fun `list with exactly ten files shows all of them`() {
        val files = tempFiles(10)

        val visible = visibleExportFiles(files, showAll = false)

        assertEquals(10, visible.size)
    }

    @Test
    fun `empty list returns empty`() {
        assertTrue(visibleExportFiles(emptyList(), showAll = false).isEmpty())
        assertTrue(visibleExportFiles(emptyList(), showAll = true).isEmpty())
    }
}
