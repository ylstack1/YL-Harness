package com.ylstack1.ylharness.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAgentManagerInstallGuardTest {

    @Test
    fun `agentNotInstalled returns null when the binary is installed`() {
        assertNull(agentNotInstalled("pi", true))
        assertNull(agentNotInstalled("opencode", true))
    }

    @Test
    fun `agentNotInstalled returns a ToolResult when pi is not installed`() {
        val result = requireNotNull(agentNotInstalled("pi", false))
        assertFalse(result.ok)
        assertTrue(result.output.contains("not installed"))
        assertTrue(result.output.contains("pi"))
        assertTrue(result.output.contains("Settings"))
    }

    @Test
    fun `agentNotInstalled returns a ToolResult when opencode is not installed`() {
        val result = requireNotNull(agentNotInstalled("opencode", false))
        assertFalse(result.ok)
        assertTrue(result.output.contains("not installed"))
        assertTrue(result.output.contains("opencode"))
        assertTrue(result.output.contains("Settings"))
    }
}
