package com.androidharness.app.tools

import org.junit.Assert.*
import org.junit.Test

class ShellAccessTest {
    @Test fun `pinned Android backends never silently run over SSH`() {
        for (access in listOf("shizuku", "root")) {
            validateShellAccess(access, remote = false)
            assertTrue(runCatching { validateShellAccess(access, remote = true) }.isFailure)
        }
        validateShellAccess("auto", remote = true)
        validateShellAccess(null, remote = true)
        assertTrue(runCatching { validateShellAccess("unknown", remote = false) }.isFailure)
    }
}
