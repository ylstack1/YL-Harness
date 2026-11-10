package com.androidharness.app.llm

import com.androidharness.app.agent.CustomReasoning
import com.androidharness.app.agent.CustomReasoningConfig
import dev.cel.common.CelOptions
import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.CelRuntime
import dev.cel.runtime.CelRuntimeFactory
import kotlinx.serialization.json.*

/** Real CEL, with bounded parsing/evaluation and no app, network, or filesystem bindings. */
object ReasoningMapping {
    private val options = CelOptions.current().maxExpressionCodePointSize(8_192)
        .maxParseRecursionDepth(64).maxParseExpressionNodeCount(2_048)
        .comprehensionMaxIterations(1_000).maxRegexProgramSize(1_000).build()
    private val compiler by lazy { CelCompilerFactory.standardCelCompilerBuilder()
        .setOptions(options).addVar("reasoningLevel", SimpleType.STRING).build() }
    private val runtime by lazy { CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(options).build() }
    private val programs = object : LinkedHashMap<String, CelRuntime.Program>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CelRuntime.Program>?) = size > 16
    }
    private fun program(expression: String): CelRuntime.Program = synchronized(programs) {
        require(expression.length <= 8_192) { "Mapping must be at most 8,192 characters." }
        programs.getOrPut(expression) { runtime.createProgram(compiler.compile(expression).ast) }
    }

    fun evaluate(expression: String, level: String): JsonObject {
        try {
            val value = toJson(program(expression).eval(mapOf("reasoningLevel" to CustomReasoning.wire(level))))
            require(value is JsonObject) { "The CEL expression must return a JSON object." }
            require(value.toString().length <= 32_768) { "Mapping result is too large." }
            return value
        } catch (e: Exception) {
            throw IllegalArgumentException("Reasoning mapping: ${e.message?.take(500) ?: "Invalid expression"}", e)
        }
    }

    fun validate(config: CustomReasoningConfig, provider: ProviderConfig, spec: com.androidharness.app.agent.ThinkingSpecs.Spec): CustomReasoningConfig {
        val normalized = CustomReasoning.normalized(config)
        if (ReasoningDefaults.isLocal(provider)) {
            require(normalized.mapping.isBlank()) { "Local models use native thinking controls; CEL request mapping is unavailable." }
            return normalized
        }
        val expression = ReasoningDefaults.effective(normalized, provider, spec)
        require(expression != null) { "No inherited reasoning mapping is available for this model/provider. Add a CEL mapping." }
        // Validate the effective mapping, not membership in a separate preset vocabulary.
        normalized.levels.forEach { evaluate(expression, it) }
        return normalized
    }

    /** Recursive JSON merge; explicit null removes a field. */
    fun merge(body: JsonObject, patch: JsonObject): JsonObject {
        val result = body.toMutableMap()
        patch.forEach { (key, value) ->
            when {
                value == JsonNull -> result.remove(key)
                value is JsonObject -> result[key] = merge(result[key] as? JsonObject ?: JsonObject(emptyMap()), value)
                else -> result[key] = value
            }
        }
        return JsonObject(result)
    }

    private fun toJson(value: Any?, depth: Int = 0): JsonElement {
        require(depth <= 32) { "Mapping result is too deeply nested." }
        return when (value) {
            null, com.google.protobuf.NullValue.NULL_VALUE -> JsonNull
            is dev.cel.common.values.NullValue -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> { require(value.toDouble().isFinite()) { "JSON numbers must be finite." }; JsonPrimitive(value) }
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
                require(key is String) { "JSON object keys must be strings." }
                key to toJson(item, depth + 1)
            })
            is List<*> -> JsonArray(value.map { toJson(it, depth + 1) })
            else -> error("Mapping must contain only JSON values.")
        }
    }
}
