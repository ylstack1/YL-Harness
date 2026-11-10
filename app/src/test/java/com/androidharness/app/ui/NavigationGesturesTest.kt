package com.androidharness.app.ui

import org.junit.Assert.*
import org.junit.Test

class NavigationGesturesTest {
    @Test fun settingsAndEditingSurfacesKeepTheirOwnSwipes() {
        for (route in listOf(null, "settings", "providers", "skills", "stats", "setup",
            "viewer/{path}", "project/{projectId}/memory")) {
            assertFalse("Drawer swipe on $route", drawerSwipeAllowed(route))
        }
    }
    @Test fun workspaceRoutesRetainDrawerNavigation() {
        for (route in listOf("chat?kind={kind}", "chat/{sessionId}?messageId={messageId}",
            "terminal?sessionId={sessionId}", "files?sessionId={sessionId}", "projects", "project/{projectId}")) {
            assertTrue("Drawer swipe on $route", drawerSwipeAllowed(route))
        }
    }
}
