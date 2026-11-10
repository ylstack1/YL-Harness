package com.androidharness.app.core

import kotlinx.serialization.Serializable

/** Harness-owned evidence, separate from untrusted text returned by a website. */
@Serializable
data class ResearchReceipt(
    val sourcesUsed: Int? = null,
    val sourcesMax: Int? = null,
    val pagesRead: Int? = null,
    val readsRequired: Int? = null,
    val pageUrl: String? = null,
    val pageRead: Boolean = false,
    val backend: String? = null,
)
