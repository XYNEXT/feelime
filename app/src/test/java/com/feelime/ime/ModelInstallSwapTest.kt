package com.feelime.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInstallSwapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a failed replacement restores the old root`() {
        val root = File(tmp.root, "model").apply { mkdirs(); File(this, "version").writeText("old") }
        val stage = File(tmp.root, ".model.import.part").apply { mkdirs(); File(this, "version").writeText("new") }
        val previous = File(tmp.root, ".model.previous")

        ModelInstallSwap.install(root, stage, previous)

        assertEquals("new", File(root, "version").readText())
        assertFalse(previous.exists())
        assertFalse(stage.exists())
    }

    @Test
    fun `failed restore keeps the only old backup and reports it`() {
        val root = File(tmp.root, "model").apply { mkdirs(); File(this, "version").writeText("old") }
        val stage = File(tmp.root, ".model.import.part").apply { mkdirs(); File(this, "version").writeText("new") }
        val previous = File(tmp.root, ".model.previous")

        val error = runCatching {
            ModelInstallSwap.install(
                root,
                stage,
                previous,
                move = { from, to ->
                    when {
                        from == root && to == previous -> from.renameTo(to)
                        from == stage && to == root -> false
                        from == previous && to == root -> false
                        else -> from.renameTo(to)
                    }
                },
            )
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(error?.message.orEmpty().contains(previous.name))
        assertTrue(previous.isDirectory)
        assertEquals("old", File(previous, "version").readText())
        assertTrue(stage.isDirectory)
        assertFalse(root.exists())
    }

    @Test
    fun `an unfinished backup is never deleted before a new install`() {
        val root = File(tmp.root, "model").apply { mkdirs(); File(this, "version").writeText("current") }
        val stage = File(tmp.root, ".model.import.part").apply { mkdirs(); File(this, "version").writeText("new") }
        val previous = File(tmp.root, ".model.previous").apply { mkdirs(); File(this, "version").writeText("backup") }

        val error = runCatching { ModelInstallSwap.install(root, stage, previous) }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertEquals("current", File(root, "version").readText())
        assertEquals("backup", File(previous, "version").readText())
        assertEquals("new", File(stage, "version").readText())
    }
}
