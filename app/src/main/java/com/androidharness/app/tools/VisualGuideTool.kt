package com.androidharness.app.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Detailed drawing rules, loaded only when a reply is about to include a visual. The always-on prompt carries a
 * short summary; this keeps the deep guidance out of every request. Keep the hx-* reference in step with
 * ArtifactSandbox.DESIGN_KIT.
 */
internal object VisualGuide {
    val modules = linkedMapOf(
        "basics" to BASICS,
        "diagram" to DIAGRAM,
        "chart" to CHART,
        "interactive" to INTERACTIVE,
        "mockup" to MOCKUP,
        "art" to ART,
    )

    fun read(requested: List<String>): String {
        val names = requested.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val unknown = names.filterNot { it in modules }
        if (unknown.isNotEmpty()) throw ToolFailure("Unknown module: ${unknown.joinToString()}. Use ${modules.keys.joinToString()}.")
        // basics always comes first: every module builds on its fence, tokens and kit.
        val chosen = (listOf("basics") + names).distinct()
        return chosen.joinToString("\n\n") { "## ${it.replaceFirstChar(Char::uppercase)}\n${modules.getValue(it).trim()}" }
    }

    private const val BASICS = """
FENCES. Write the markup directly; never wrap it in JSON.
```harness-html title="Short title" loading="Drawing the orbits"
<div class="hx-stack">…</div>
<script>…</script>
```
```harness-svg title="Short title"
<svg viewBox="0 0 640 360" role="img" aria-label="What the diagram shows">…</svg>
```
- title: 2-5 words shown above the visual. loading: optional 2-6 word status shown while it streams; keep it plain for serious topics.
- harness-svg must contain exactly one <svg> element. Use harness-html when you need script, controls or several elements.
- Put prose before or after the fence; never explain the code inside the reply unless asked. Never show the source as a code block unless the user asks for source.
- Limits: under 24 KB when possible, 64 KB maximum. Self-contained and offline: no CDN, remote fonts, images, fetch or forms. Data and images must be inline (SVG, canvas, data: URLs).

THEME TOKENS (CSS variables, already set; they follow the user's app theme, light or dark):
--background --foreground --muted --surface --surface-2 --border --accent --accent-fg (text on accent) --accent-soft
--success --warning --danger, chart series --c1 … --c6, --radius (14px) --radius-sm (10px) --mono.
Never hard-code colors: use these tokens so the visual matches the app and works in dark mode. The body already has 16px padding, the app font and the background color.

DESIGN KIT (class names ready to use; prefer them over writing CSS):
Layout: hx-stack (vertical, 12px gap), hx-row (wrapping row), hx-spread (space-between row), hx-grid (responsive cards, 140px minimum).
Surfaces: hx-card, hx-card-accent. Text: hx-title, hx-subtitle, hx-muted, hx-label (small caps label), hx-stat (big number), hx-mono.
Controls: hx-btn (filled), hx-btn-ghost, aria-pressed="true" marks a selected button, hx-segment (segmented control: a wrapper of buttons), hx-range (slider).
Status: hx-chip with data-tone="accent|success|warning|danger". Progress: <div class="hx-bar"><span style="width:62%"></span></div>.
Data: hx-table (add class num to numeric cells). Misc: hx-divider, hx-fade (gentle entrance).
SVG classes: hx-text, hx-text-muted, hx-node, hx-node-accent, hx-edge, hx-edge-accent, hx-grid-line.

JS HELPER: harness.palette() returns the series colors as hex strings; harness.color('accent') reads any token; harness.state('Plain summary of current values') passes the current selection to the user's next message (context only; it never sends or runs anything).

QUALITY BAR:
- Phone first: design for 360px wide and let it scale. No horizontal scrolling; stack on narrow screens.
- One clear idea per visual. Generous spacing, 2-3 text sizes, sentence-case labels, no decorative gradients or emoji clutter.
- Touch targets at least 44px. Label every control. Text contrast must stay readable in both themes.
- Respect prefers-reduced-motion (the base stylesheet already disables animation when it is set).
- Only real or clearly labelled illustrative data. Never invent measurements to fill a chart.
- Every script must run without errors; guard against missing elements.
"""

    private const val DIAGRAM = """
Use harness-svg for static diagrams: flows, architectures, timelines, hierarchies, cycles, comparisons of structure.
- viewBox around 640 wide; height to fit (300-520). width="100%" is applied automatically. Keep 24px outer margin.
- Boxes: <rect class="hx-node" rx="12"> (use hx-node-accent for the one element the diagram is about). Labels: <text class="hx-text" text-anchor="middle" dominant-baseline="middle">. Secondary text: hx-text-muted.
- Connectors: <path class="hx-edge" marker-end="url(#arrow)">. Define the arrow once:
  <defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="var(--muted)"/></marker></defs>
- Size boxes to their text: about 7.5px per character at 13px plus 24px padding; wrap long labels into two <tspan> lines instead of shrinking fonts. Minimum font 12px.
- Lay out on a grid, align centers, keep consistent gaps (≥ 28px between boxes). Route edges orthogonally or with gentle curves; avoid crossings.
- 3-9 nodes is ideal. Split anything larger into two diagrams or group nodes into labelled regions (<rect fill="none" stroke="var(--border)" stroke-dasharray="4 4">).
- Show the real mechanism (what flows where, and why), not a decorative box grid. Give the svg an aria-label that states the takeaway.
- Mermaid also renders from a ```mermaid code block when a simple flowchart or sequence diagram is enough; prefer harness-svg when layout quality matters.
"""

    private const val CHART = """
First choose: for a simple bar or line chart of a few series, the native {"type":"chart"} harness-ui component is better (it is interactive and accessible). Use a custom chart only for forms it cannot draw: scatter, area, stacked, donut, histogram, ranges, annotated timelines, small multiples.
- Draw with inline SVG (preferred, crisp) or <canvas> sized for devicePixelRatio. Colors from harness.palette() or var(--c1)…; never hard-code.
- Structure: title in hx-subtitle, one-line takeaway in hx-muted, then the plot, then a legend as hx-row of hx-chip items with a color dot.
- Axes: light grid lines (hx-grid-line), muted tick labels at 12px, units in the axis label, tabular numbers. Start bar axes at zero.
- Direct-label lines and bars when possible instead of relying on the legend. At most 6 series; highlight the one that matters and mute the rest.
- Tap support: on pointer events show the exact value in a small hx-card tooltip or a caption line; update harness.state with the selected point.
- Donut/pie only for parts of one whole with ≤ 5 slices; otherwise use bars.
- Include the data: either exact labels on marks or a small hx-table below for exact values.
"""

    private const val INTERACTIVE = """
Use harness-html for explorable explanations, calculators beyond the native calculator, simulations, quizzes, step-throughs and small tools.
- Structure: header (hx-title + one-line hx-muted purpose), controls row, live result area, short note on assumptions.
- Controls: hx-range sliders with a visible value, hx-segment for 2-4 modes, hx-btn for the primary action. Update results on input events immediately; no submit buttons.
- Keep state in a single JS object and one render() function that redraws from it. Initialise with sensible defaults so the first frame is already meaningful.
- After each change call harness.state('Rate 4.5%, 20 years: total 52,300') with the current values so the user's follow-up question can use them.
- Animation: requestAnimationFrame, pause when off-screen or finished, and skip it under prefers-reduced-motion.
- Quizzes and step-throughs: show one item at a time, give immediate feedback with hx-chip tones, and keep a visible progress bar (hx-bar).
- No storage APIs, timers that never stop, alert/confirm/prompt dialogs, or navigation. Everything must work offline.
"""

    private const val MOCKUP = """
Use harness-html for UI mockups and screen concepts.
- Frame a phone screen as a centered column max-width 380px with hx-card sections; for wide layouts use hx-grid.
- Use real-looking content (plausible names, numbers and copy), not lorem ipsum. Show one primary action per screen.
- Represent icons with simple inline SVG strokes (currentColor, 1.75px) or short text labels; no icon fonts or remote images.
- Show states when relevant (empty, loading, error) with an hx-segment that switches between them.
- Annotate design decisions in hx-muted captions below the frame rather than over it.
"""

    private const val ART = """
Use harness-svg (or harness-html with canvas for generative or animated pieces) for illustrations, patterns, icons and visual explanations of physical things.
- Compose on a clear focal point with a limited palette built from the theme tokens; layered shapes and soft opacity read better than detail.
- Keep path counts moderate (< 400 elements) so it renders quickly on phones.
- Generative canvas art: seed a small PRNG so a redraw button can produce variations; cap work per frame and respect reduced motion.
- Always include an aria-label describing the image.
"""
}

/** Read-only and workspace-independent, so it is offered in both Chat and Code. */
class VisualGuideTool : Tool {
    override val name = "visual_guide"
    override val description =
        "Load detailed design rules before drawing a custom visual in a reply (harness-html or harness-svg fence). " +
            "Call it once per conversation before your first custom visual of a kind; it is not needed for native harness-ui " +
            "components or plain Markdown. Modules: diagram (flows, architecture, timelines), chart (custom data visualisation), " +
            "interactive (explorable widgets, calculators, simulations, quizzes), mockup (UI screens), art (illustration, generative). " +
            "The basics (fences, theme tokens, hx-* design kit, harness JS helper) are always included."
    override val parametersSchema = Schema.obj(
        mapOf("modules" to Schema.array(Schema.string("diagram, chart, interactive, mockup or art"),
            "The modules that fit the visual you are about to make. Pick all that apply.")),
        required = listOf("modules"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val modules = when (val raw = args["modules"]) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> raw.contentOrNull?.split(',').orEmpty()
            else -> emptyList()
        }
        return ToolResult(true, VisualGuide.read(modules))
    }
}
