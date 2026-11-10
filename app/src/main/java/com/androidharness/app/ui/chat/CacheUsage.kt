package com.androidharness.app.ui.chat

import com.androidharness.app.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.androidharness.app.data.db.UsageEventEntity
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.ui.theme.LocalStatusColors
import java.util.Locale

internal data class CacheUsageSummary(
    val input: Long,
    val cached: Long,
    val hits: Int,
    val misses: Int,
    val unknown: Int,
) {
    val rate: Double? get() = if (input > 0) cached * 100.0 / input else null
    val label: String get() = when {
        rate == null -> "Cache unavailable"
        cached == 0L -> "Cache missed"
        else -> "Cache hit · ${cachePercentage(cached, input)}%"
    } + if (unknown > 0 && rate != null) " · partial" else ""
}

/** Two decimals, reserving 100% for a fully cached prompt and 0% for a true miss. */
internal fun cachePercentage(cached: Long, input: Long): String = when {
    cached == input -> "100"
    cached * 100.0 / input < 0.01 -> "<0.01"
    else -> java.math.BigDecimal.valueOf(cached).multiply(java.math.BigDecimal.valueOf(100))
        .divide(java.math.BigDecimal.valueOf(input), 2, java.math.RoundingMode.HALF_UP)
        .min(java.math.BigDecimal("99.99"))
        .stripTrailingZeros().toPlainString()
}

internal fun validCacheUsage(row: UsageEventEntity): Boolean = row.cacheReported &&
    row.inputTokens > 0 && row.cachedTokens in 0..row.inputTokens &&
    row.cacheWriteTokens in 0..(row.inputTokens - row.cachedTokens)

internal fun cacheUsageSummary(rows: List<UsageEventEntity>): CacheUsageSummary {
    val known = rows.filter(::validCacheUsage)
    return CacheUsageSummary(
        input = known.sumOf { it.inputTokens },
        cached = known.sumOf { it.cachedTokens.coerceIn(0, it.inputTokens) },
        hits = known.count { it.cachedTokens > 0 },
        misses = known.count { it.cachedTokens <= 0 },
        unknown = rows.size - known.size,
    )
}

/** Rows arrive in recorded request order; unknown latest usage must not reuse an older hit. */
internal fun cacheIndicatorSummary(rows: List<UsageEventEntity>, running: Boolean): CacheUsageSummary =
    cacheUsageSummary(if (running) rows.takeLast(1) else rows)

/** Cache reads only; cache writes and uncached input are separate charges. */
internal fun cacheReadCost(row: UsageEventEntity, price: ModelsDev.CachePrices?): Double? =
    if (!validCacheUsage(row)) null
    else if (row.cachedTokens <= 0) 0.0
    else price?.takeIf { it.read != null }?.let { row.cachedTokens.coerceIn(0, row.inputTokens.coerceAtLeast(0)) / 1_000_000.0 * it.read!! }

/** Input charges on a miss include any cache creation premium, never output. */
internal fun cacheMissCost(row: UsageEventEntity, price: ModelsDev.CachePrices?): Double? {
    if (!validCacheUsage(row)) return null
    val cached = row.cachedTokens.coerceIn(0, row.inputTokens)
    val writes = row.cacheWriteTokens.coerceIn(0, row.inputTokens - cached)
    val uncached = row.inputTokens - cached - writes
    if (writes > 0 && price?.write == null) return null
    return price?.let { (uncached * it.input + writes * (it.write ?: 0.0)) / 1_000_000.0 }
}

private fun cacheMoney(cost: Double): String = when {
    cost == 0.0 -> "$0"
    cost < 0.0001 -> "<$0.0001"
    else -> String.format(Locale.US, "$%.4f", cost)
}

/** One quiet footer line; request-level amounts stay behind a tap, inside chat. */
@Composable
internal fun CacheUsageFooter(rows: List<UsageEventEntity>, running: Boolean = false, speed: TurnSpeed? = null) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val speedLabel = turnPerformanceLabel(null, speed?.copy(firstTokenMs = null))
    if (rows.isEmpty() && speedLabel.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    val summary = remember(rows) { cacheUsageSummary(rows) }
    val indicator = remember(rows, running) { cacheIndicatorSummary(rows, running) }
    val prices = remember(rows) {
        rows.map { row -> row.inputPrice?.let { ModelsDev.CachePrices(it, row.cacheReadPrice, row.cacheWritePrice) } }
    }
    val costs = rows.mapIndexed { index, row ->
        if (summary.cached > 0) cacheReadCost(row, prices[index]) else cacheMissCost(row, prices[index])
    }
    val total = if (costs.all { it != null }) costs.filterNotNull().sum() else null
    val successColor = LocalStatusColors.current.success
    val errorColor = MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = rows.isNotEmpty()) { expanded = !expanded }.heightIn(min = 36.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (speedLabel.isNotBlank()) {
                Text(speedLabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f).padding(end = 8.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
            if (indicator.rate != null) {
                Icon(
                    if (indicator.cached > 0) Icons.Filled.CheckCircle else Icons.Filled.Close,
                    contentDescription = if (indicator.cached > 0) uiContext.getString(R.string.ui_cache_hit) else uiContext.getString(R.string.ui_cache_missed),
                    tint = if (indicator.cached > 0) successColor else errorColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                indicator.label + if (running) uiContext.getString(R.string.ui_last_completed) else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
                textAlign = TextAlign.End,
            )
            if (rows.isNotEmpty()) Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) uiContext.getString(R.string.ui_hide_cache_details) else uiContext.getString(R.string.ui_show_cache_details),
                Modifier.padding(start = 2.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running) Text(uiContext.getString(R.string.ui_last_completed_request, (indicator.label).toString()),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val costLabel = if (summary.cached > 0) uiContext.getString(R.string.ui_cache_hit_cost_this_turn) else uiContext.getString(R.string.ui_input_cost_this_turn)
                Text("$costLabel: ${total?.let { "est. ${cacheMoney(it)}" } ?: "unavailable"}",
                    style = MaterialTheme.typography.bodySmall)
                Text(uiContext.getString(R.string.ui_of_input_tokens_served_from_cache, (summary.cached).toString(), (summary.input).toString()),
                    style = MaterialTheme.typography.bodySmall)
                Text(if (summary.cached > 0) uiContext.getString(R.string.ui_estimated_cached_reads_only_excluding_output_and)
                    else uiContext.getString(R.string.ui_estimated_uncached_input_and_cache_writes_excluding),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (summary.unknown > 0) Text(uiContext.getString(R.string.ui_requests_did_not_report_cache_usage_excluded, (summary.unknown).toString()),
                    style = MaterialTheme.typography.labelSmall)
                if (rows.size > 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    rows.forEachIndexed { i, r ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (validCacheUsage(r)) {
                                    Icon(
                                        if (r.cachedTokens > 0) Icons.Filled.CheckCircle else Icons.Filled.Close,
                                        contentDescription = if (r.cachedTokens > 0) uiContext.getString(R.string.ui_cache_hit) else uiContext.getString(R.string.ui_cache_miss),
                                        tint = if (r.cachedTokens > 0) successColor else errorColor,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(uiContext.getString(R.string.ui_request, (i + 1).toString()), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                if (!validCacheUsage(r)) uiContext.getString(R.string.ui_unavailable)
                                else if (r.cachedTokens > 0) "${r.cachedTokens} cached"
                                else "0 cached",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
