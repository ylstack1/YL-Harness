package com.androidharness.app.ui.common

import com.androidharness.app.data.env.EnvState
import org.junit.Assert.*
import org.junit.Test

class PackageInstallProgressTest {
    @Test fun `download labels follow received bytes and the current package`() {
        val start = EnvState.Downloading(1, 4, "python", 0, 8192).packageProgress()!!
        val half = EnvState.Downloading(1, 4, "python", 4096, 8192).packageProgress()!!
        assertEquals("Downloading python", half.title)
        assertEquals("Package 2 of 4 · 4.0 KB / 8.0 KB", half.detail)
        assertEquals("0%", start.percentage)
        assertEquals("50%", half.percentage)
        assertEquals(0.5f, half.fraction!!, 0f)
    }

    @Test fun `unknown sizes stay indeterminate while showing actual received bytes`() {
        val progress = EnvState.Downloading(0, 1, "jq", 2048).packageProgress()!!
        assertNull(progress.fraction)
        assertNull(progress.percentage)
        assertEquals("Package 1 of 1 · 2.0 KB", progress.detail)
    }

    @Test fun `installation counts only finished packages and never finishes early`() {
        val first = EnvState.Installing(0, 4, "liba").packageProgress()!!
        val last = EnvState.Installing(3, 4, "python").packageProgress()!!
        assertEquals("0%", first.percentage)
        assertEquals("Installing python", last.title)
        assertEquals("3 of 4 packages installed", last.detail)
        assertEquals("75%", last.percentage)
        assertNull(EnvState.Ready.packageProgress())
        assertNull(EnvState.Failed("offline").packageProgress())
    }

    @Test fun `preparing does not invent a percentage and downloads clamp to their byte range`() {
        assertNull(EnvState.Preparing.packageProgress()!!.fraction)
        assertEquals("100%", EnvState.Downloading(0, 1, "jq", 9000, 8192).packageProgress()!!.percentage)
        assertEquals("0%", EnvState.Downloading(0, 1, "jq", -1, 8192).packageProgress()!!.percentage)
    }
}
