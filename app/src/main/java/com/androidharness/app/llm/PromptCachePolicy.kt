package com.androidharness.app.llm

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Optional session routing hints are endpoint-specific, not an OpenAI-compatible capability. */
internal object PromptCachePolicy {
    // OpenAI documents prompt_cache_key; OpenRouter accepts it for sticky routing:
    // https://developers.openai.com/api/docs/guides/prompt-caching
    // https://openrouter.ai/docs/guides/best-practices/prompt-caching
    // Other gateways may cache prompts automatically but reject these request fields (#42).
    fun supportsKey(baseUrl: String): Boolean = when (baseUrl.toHttpUrlOrNull()?.host) {
        "api.openai.com", "openrouter.ai" -> true
        else -> false
    }
}
