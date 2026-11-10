package com.androidharness.app.local

enum class LocalBenchmarkMode { CPU, GPU, BOTH }
enum class LocalBenchmarkPhase { WARMUP, GENERATION, PROMPT, FINAL }

val LOCAL_BENCHMARK_PROMPTS = listOf(
    "What is 17 + 25? Reply with only the number.",
    "Summarize in one sentence: Trees absorb carbon dioxide and release oxygen. Their roots hold soil in place and reduce erosion.",
    "Write a Python function named square that returns the square of a number. Output only the function.",
)
data class LocalBenchmarkAnswer(val backend: LocalBackend, val generationThreads: Int, val promptThreads: Int,
    val promptIndex: Int, val text: String, val matchesReference: Boolean?, val phase: LocalBenchmarkPhase, val inputTokens: Int = 0, val outputTokens: Int = 0)

data class LocalBenchmarkSample(
    val backend: LocalBackend,
    val phase: LocalBenchmarkPhase,
    val generationThreads: Int,
    val promptThreads: Int,
    val generationTps: Double,
    val promptTps: Double,
)
data class LocalBenchmarkRecommendation(
    val backend: LocalBackend,
    val generationThreads: Int,
    val promptThreads: Int,
    val generationTps: Double,
    val promptTps: Double,
)
data class LocalBenchmarkProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val backend: LocalBackend? = null,
    val phase: LocalBenchmarkPhase = LocalBenchmarkPhase.WARMUP,
    val generationThreads: Int = 0,
    val promptThreads: Int = 0,
    val repeat: Int = 0,
    val livePhase: String = "",
    val liveTokens: Int = 0,
    val liveTps: Double = 0.0,
    val livePromptTps: Double = 0.0,
    val liveGenerationTps: Double = 0.0,
    val currentPrompt: Int = 0,
    val liveAnswer: String = "",
    val answers: List<LocalBenchmarkAnswer> = emptyList(),
    val samples: List<LocalBenchmarkSample> = emptyList(),
    val errors: List<String> = emptyList(),
    val recommendations: List<LocalBenchmarkRecommendation> = emptyList(),
    val finished: Boolean = false,
    val stopped: Boolean = false,
)

internal fun localBenchmarkThreadCounts(cores: Int, current: Int, currentPrompt: Int = current): List<Int> {
    val max = cores.coerceAtLeast(1)
    return listOf(1, 2, 4, max.coerceAtMost(8), current.coerceIn(1, max), currentPrompt.coerceIn(1, max))
        .filter { it <= max }.distinct().sorted()
}
