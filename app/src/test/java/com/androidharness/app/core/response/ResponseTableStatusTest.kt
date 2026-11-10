package com.androidharness.app.core.response

import org.junit.Assert.*
import org.junit.Test

class ResponseTableStatusTest {
    @Test fun onlyExplicitReportedStatusesBecomeBadges() {
        assertEquals(ResponseTableData.Status("passed", "The logo renders."), ResponseTableData.status("Pass. The logo renders."))
        assertEquals(ResponseTableData.Status("failed", "The response was empty."), ResponseTableData.status("Failed: The response was empty."))
        assertEquals(ResponseTableData.Status("unverified", ""), ResponseTableData.status("Unverified"))
        assertNull(ResponseTableData.status("Pass rate improved"))
        assertNull(ResponseTableData.status("Passing through the gateway"))
        assertNull(ResponseTableData.status("No result yet"))
    }
}
