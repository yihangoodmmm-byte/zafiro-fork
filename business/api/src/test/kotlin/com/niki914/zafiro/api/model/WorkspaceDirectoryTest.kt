package com.niki914.zafiro.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceDirectoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun acceptsDirectoryWithSpacesAndNormalizesTrailingSlash() {
        val directory = temp.newFolder("project with spaces")
        assertEquals(directory.canonicalPath, WorkspaceDirectory.resolve(directory.path + "/"))
    }

    @Test
    fun rejectsFilesMissingPathsAndInvalidInput() {
        assertNull(WorkspaceDirectory.resolve(temp.newFile("plain.txt").path))
        assertNull(WorkspaceDirectory.resolve(temp.root.resolve("missing").path))
        assertNull(WorkspaceDirectory.absolutePath("relative/path"))
        assertNull(WorkspaceDirectory.absolutePath("/folder\u0000suffix"))
    }
}
