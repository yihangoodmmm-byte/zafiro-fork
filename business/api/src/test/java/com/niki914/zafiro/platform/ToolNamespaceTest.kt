package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.conflict.QualifiedToolName
import com.niki914.zafiro.platform.conflict.ToolNamespace
import com.niki914.zafiro.platform.conflict.ToolResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolNamespaceTest {

    @Test
    fun `qualified name resolves exactly`() {
        val tools = listOf(
            QualifiedToolName("native", "browser_snapshot"),
            QualifiedToolName("ext.mobile", "browser_snapshot"),
        )

        val result = ToolNamespace.resolve(tools, "ext.mobile.browser_snapshot")

        assertEquals(QualifiedToolName("ext.mobile", "browser_snapshot"), (result as ToolResolution.Resolved).tool)
    }

    @Test
    fun `bare name with two providers is ambiguous without a default`() {
        val tools = listOf(
            QualifiedToolName("native", "browser_snapshot"),
            QualifiedToolName("ext.mobile", "browser_snapshot"),
        )

        val result = ToolNamespace.resolve(tools, "browser_snapshot")

        assertTrue(result is ToolResolution.Ambiguous)
        assertEquals(2, (result as ToolResolution.Ambiguous).candidates.size)
    }

    @Test
    fun `default provider settles the ambiguity`() {
        val tools = listOf(
            QualifiedToolName("native", "browser_snapshot"),
            QualifiedToolName("ext.mobile", "browser_snapshot"),
        )

        val result = ToolNamespace.resolve(tools, "browser_snapshot", defaultProvider = "native")

        assertEquals(QualifiedToolName("native", "browser_snapshot"), (result as ToolResolution.Resolved).tool)
    }

    @Test
    fun `unknown tool is not found`() {
        val result = ToolNamespace.resolve(emptyList(), "browser_snapshot")

        assertTrue(result is ToolResolution.NotFound)
    }

    @Test
    fun `duplicates are reported per provider`() {
        val tools = listOf(
            QualifiedToolName("native", "shell"),
            QualifiedToolName("native", "shell"),
        )

        assertEquals(listOf(QualifiedToolName("native", "shell")), ToolNamespace.duplicates(tools))
    }
}
