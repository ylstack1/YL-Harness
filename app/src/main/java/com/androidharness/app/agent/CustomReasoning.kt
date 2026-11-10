package com.androidharness.app.agent

import com.androidharness.app.llm.ProviderConfig
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class CustomReasoningConfig(val levels: List<String>, val mapping: String = "")

/** Custom vocabulary belongs to one connection and model, never to an unrelated fallback. */
object CustomReasoning {
    const val LEVEL_HELP = "Set the reasoning levels available in chat. Order them from lowest to highest reasoning effort. Do not configure reasoning levels the model does not support."
    fun key(config: ProviderConfig): String {
        val target = ThinkingSpecs.routedConfig(config)
        return MessageDigest.getInstance("SHA-256")
            .digest(listOf(target.id, target.type.name, target.baseUrl.trimEnd('/'), target.model).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun wire(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), " ")
        .let { when (it) { "off", "none", "disabled" -> "disabled"; "x high", "x highest", "xhighest", "extra high", "extra highest" -> "xhigh"; else -> it } }

    fun label(value: String): String = when (val id = wire(value)) {
        "disabled" -> "Disabled"
        "xhigh" -> "X-High"
        else -> id.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    fun native(value: String): ThinkingLevel? = when (wire(value)) {
        "disabled" -> ThinkingLevel.OFF
        "on", "enabled" -> ThinkingLevel.HIGH
        else -> ThinkingLevel.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == wire(value) }
    }

    fun normalized(config: CustomReasoningConfig): CustomReasoningConfig {
        require(config.levels.isNotEmpty()) { "Add at least one reasoning level." }
        require(config.levels.size <= 16) { "Use at most 16 reasoning levels." }
        require(config.levels.all { it.isNotBlank() && it.length <= 32 && it.none(Char::isISOControl) }) {
            "Each level needs a name of 1–32 characters."
        }
        val levels = config.levels.map(::label)
        require(levels.map(::wire).distinct().size == levels.size) { "Reasoning levels must be unique (ignoring case)." }
        return config.copy(levels = levels, mapping = config.mapping.trim())
    }

    fun defaultLevels(spec: ThinkingSpecs.Spec): List<String> = spec.reportedLevels
        ?: spec.levels.map { if (spec.style == ThinkingSpecs.Style.TOGGLE && it != ThinkingLevel.OFF) "On" else it.label }
            .ifEmpty { listOf("On") }

    fun resolve(provider: ProviderConfig, requested: ThinkingLevel, maxOutput: Int, entry: com.androidharness.app.llm.ModelEntry?,
        config: CustomReasoningConfig?, selected: String?): ThinkingSpecs.ResolvedThinking {
        val base = ThinkingSpecs.resolve(provider, requested, maxOutput, entry)
        // Preserve newly reported vocabularies even before a future app adds enum presets for them.
        val effective = config ?: base.spec.reportedLevels?.takeIf { names -> names.any { native(it) == null } }
            ?.let { CustomReasoningConfig(it) }
        if (effective == null || effective.levels.isEmpty()) return base
        val choice = effective.levels.firstOrNull { wire(it) == selected?.let(::wire) }
            ?: effective.levels.firstOrNull { config == null && native(it) == requested }
            ?: effective.levels.firstOrNull { config == null && wire(it) == entry?.defaultReasoningEffort?.let(::wire) && requested == ThinkingLevel.OFF }
            ?: effective.levels.firstOrNull { config == null && requested == ThinkingLevel.OFF && wire(it) != "disabled" }
            ?: effective.levels.lastOrNull { config == null && requested in setOf(ThinkingLevel.MAX, ThinkingLevel.ULTRA) && wire(it) != "disabled" }
            ?: effective.levels.firstOrNull { native(it) == base.level }
            ?: effective.levels.last()
        val name = wire(choice)
        if (com.androidharness.app.llm.ReasoningDefaults.isLocal(provider)) return base.copy(
            level = if (name == "disabled") ThinkingLevel.OFF else ThinkingLevel.HIGH,
            customConfig = effective, customLevel = name)
        val expression = com.androidharness.app.llm.ReasoningDefaults.effective(effective, provider, base.spec)
        val native = native(choice)
        val budget = if (effective.mapping.isBlank() && base.spec.style == ThinkingSpecs.Style.BUDGET)
            com.androidharness.app.llm.ReasoningDefaults.budgetFor(base.spec, name) else null
        val output = if (budget != null && budget > 0) maxOf(base.maxOutputTokens, budget + 4_096)
            .let { base.spec.outputMax?.let(it::coerceAtMost) ?: it } else base.maxOutputTokens
        // The selected string goes through the inherited/custom map without native-level clamping.
        return base.copy(level = native ?: if (name == "disabled") ThinkingLevel.OFF else ThinkingLevel.HIGH,
            spec = base.spec.copy(style = ThinkingSpecs.Style.AUTOMATIC), effort = null, budget = null,
            maxOutputTokens = output, customConfig = effective, customLevel = name, requestMapping = expression)
    }
}
