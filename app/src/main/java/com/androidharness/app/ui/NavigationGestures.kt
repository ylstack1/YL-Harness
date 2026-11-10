package com.androidharness.app.ui

/** Settings and editing surfaces own their swipes; normal workspace navigation stays available. */
internal fun drawerSwipeAllowed(route: String?): Boolean = route != null &&
    route !in setOf("settings", "providers", "skills", "stats", "setup") &&
    !route.startsWith("viewer/") && !route.endsWith("/memory")
