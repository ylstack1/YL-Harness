package com.androidharness.app.artifacts

/** Only the trusted outer document owns the frame. Generated code always has an opaque origin. */
object ArtifactSandbox {
    const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'"

    fun sourceDocument(artifact: ChatArtifact, dark: Boolean, responseLayout: Boolean = false,
        theme: ArtifactTheme = ArtifactTheme.fallback(dark)): String {
        artifact.validated()
        val t = theme.safe()
        val palette = t.palette.mapIndexed { index, color -> "--c${index + 1}:$color" }.joinToString(";")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="$CSP"><style>
            :root{color-scheme:${if (t.dark) "dark" else "light"};--background:${t.background};--foreground:${t.foreground};--muted:${t.muted};--surface:${t.surface};--surface-2:${t.surface2};--border:${t.border};--accent:${t.accent};--accent-fg:${t.onAccent};--accent-soft:${t.accentSoft};--success:${t.success};--warning:${t.warning};--danger:${t.danger};$palette;--radius:14px;--radius-sm:10px;--mono:ui-monospace,SFMono-Regular,Menlo,monospace;font:14px/1.55 system-ui,sans-serif;background:${t.background};color:${t.foreground}}
            *{box-sizing:border-box}body{margin:0;padding:16px;overflow-wrap:anywhere}img,svg,canvas,video{max-width:100%}button,input,select,textarea{font:inherit}button{min-height:44px;padding:8px 14px;border:1px solid var(--border);border-radius:12px;background:var(--surface);color:var(--foreground)}input,select,textarea{max-width:100%;min-height:44px}button:focus-visible,input:focus-visible{outline:2px solid var(--accent);outline-offset:2px}@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
            $DESIGN_KIT
            </style><script>$HELPER</script></head><body>${artifact.html}${if (responseLayout) MEASURE_CONTENT else ""}</body></html>""".trimIndent()
    }

    fun frameDocument(artifact: ChatArtifact, dark: Boolean, responseLayout: Boolean = false,
        theme: ArtifactTheme = ArtifactTheme.fallback(dark)): String =
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:${theme.safe().background}}iframe{width:100%;height:100%;border:0;display:block}</style></head><body><iframe id="response" title="${escape(artifact.title)}" sandbox="allow-scripts" referrerpolicy="no-referrer" srcdoc="${escape(sourceDocument(artifact, dark, responseLayout, theme))}"></iframe>${if (responseLayout) LAYOUT_HOST else ""}</body></html>"""

    /**
     * Ready-made, class-only building blocks (hx-*) so generated visuals match the app without writing CSS.
     * Nothing here restyles plain elements beyond the base rules above, so existing previews look the same.
     * Documented for models in VisualGuide; keep the two in step.
     */
    internal const val DESIGN_KIT = """.hx-stack{display:flex;flex-direction:column;gap:12px}.hx-row{display:flex;flex-wrap:wrap;align-items:center;gap:8px}.hx-spread{display:flex;align-items:center;justify-content:space-between;gap:12px}.hx-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px}
.hx-card{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius);padding:14px}.hx-card-accent{background:var(--accent-soft);border:1px solid var(--accent);border:1px solid color-mix(in srgb,var(--accent) 35%,transparent);border-radius:var(--radius);padding:14px}
.hx-title{font-size:17px;font-weight:600;line-height:1.3;margin:0}.hx-subtitle{font-size:15px;font-weight:600;margin:0}.hx-muted{color:var(--muted);font-size:13px}.hx-label{color:var(--muted);font-size:12px;font-weight:600;letter-spacing:.04em;text-transform:uppercase}.hx-stat{font-size:26px;font-weight:650;line-height:1.1;font-variant-numeric:tabular-nums}.hx-mono{font-family:var(--mono);font-size:13px}
.hx-chip{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;border-radius:999px;background:var(--surface-2);font-size:12px;font-weight:600}.hx-chip[data-tone=accent]{background:var(--accent-soft);color:var(--accent)}.hx-chip[data-tone=success]{background:var(--surface-2);background:color-mix(in srgb,var(--success) 18%,transparent);color:var(--success)}.hx-chip[data-tone=warning]{background:var(--surface-2);background:color-mix(in srgb,var(--warning) 20%,transparent);color:var(--warning)}.hx-chip[data-tone=danger]{background:var(--surface-2);background:color-mix(in srgb,var(--danger) 18%,transparent);color:var(--danger)}
.hx-btn{min-height:44px;padding:8px 16px;border-radius:999px;border:0;background:var(--accent);color:var(--accent-fg);font-weight:600;cursor:pointer}.hx-btn-ghost{min-height:44px;padding:8px 16px;border-radius:999px;border:1px solid var(--border);background:transparent;color:var(--foreground);cursor:pointer}.hx-btn[aria-pressed=true],.hx-btn-ghost[aria-pressed=true]{background:var(--accent-soft);color:var(--accent);border-color:var(--accent)}
.hx-segment{display:inline-flex;padding:3px;gap:3px;border-radius:999px;background:var(--surface-2)}.hx-segment button{min-height:36px;border:0;border-radius:999px;background:transparent;padding:6px 14px}.hx-segment button[aria-pressed=true]{background:var(--background);color:var(--accent);font-weight:600}
.hx-divider{height:1px;background:var(--border);border:0;margin:4px 0}.hx-bar{height:8px;border-radius:999px;background:var(--surface-2);overflow:hidden}.hx-bar>span{display:block;height:100%;border-radius:inherit;background:var(--accent)}
.hx-table{width:100%;border-collapse:collapse;font-size:13px}.hx-table th{color:var(--muted);font-weight:600;text-align:left}.hx-table th,.hx-table td{padding:8px 6px;border-bottom:1px solid var(--border)}.hx-table td.num,.hx-table th.num{text-align:right;font-variant-numeric:tabular-nums}
.hx-range{width:100%;accent-color:var(--accent)}.hx-fade{animation:hx-in .35s ease-out both}@keyframes hx-in{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}
.hx-svg{display:flex;justify-content:center}.hx-svg svg{width:100%;height:auto}svg .hx-text{fill:var(--foreground);font:13px system-ui,sans-serif}svg .hx-text-muted{fill:var(--muted);font:12px system-ui,sans-serif}svg .hx-node{fill:var(--surface);stroke:var(--border);stroke-width:1.5}svg .hx-node-accent{fill:var(--accent-soft);stroke:var(--accent);stroke-width:1.5}svg .hx-edge{fill:none;stroke:var(--muted);stroke-width:1.5}svg .hx-edge-accent{fill:none;stroke:var(--accent);stroke-width:2}svg .hx-grid-line{stroke:var(--border);stroke-width:1}"""

    /** window.harness: theme colors for canvas/SVG code and the existing follow-up context message. */
    private const val HELPER = """window.harness={palette:function(){var s=getComputedStyle(document.documentElement),r=[];for(var i=1;i<=8;i++){var v=s.getPropertyValue('--c'+i).trim();if(v)r.push(v)}return r},color:function(name){return getComputedStyle(document.documentElement).getPropertyValue('--'+name).trim()},state:function(summary){try{parent.postMessage({type:'harness:state',summary:String(summary).slice(0,1500)},'*')}catch(e){}}};"""

    // Only bounded presentation metadata crosses this boundary. No tool calls, navigation, or native execution.
    private const val MEASURE_CONTENT = """<script>(function(){
      let queued=false;function report(){if(queued)return;queued=true;requestAnimationFrame(function(){queued=false;let b=document.body;parent.postMessage({type:'harness:height',height:Math.ceil(Math.max(b.scrollHeight,b.getBoundingClientRect().height))},'*')})}
      if(typeof ResizeObserver!=='undefined')new ResizeObserver(report).observe(document.body);
      addEventListener('load',report);if(document.fonts)document.fonts.ready.then(report);report();
    })();</script>"""
    private const val LAYOUT_HOST = """<script>(function(){let height=220,summary='';const frame=document.getElementById('response');
      addEventListener('message',function(e){if(e.source!==frame.contentWindow||!e.data)return;
        if(e.data.type==='harness:height'&&typeof e.data.height==='number'&&Number.isFinite(e.data.height))height=Math.max(80,Math.min(12000,Math.round(e.data.height)));
        else if(e.data.type==='harness:state'&&typeof e.data.summary==='string')summary=e.data.summary.slice(0,1500);else return;
        document.title='harness-response:'+JSON.stringify({height:height,summary:summary});
      });})();</script>"""

    fun origin(artifact: ChatArtifact) = "https://concept-${artifact.id}-${artifact.revision}.invalid/"

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}
