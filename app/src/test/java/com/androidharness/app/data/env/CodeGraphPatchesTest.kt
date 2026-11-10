package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundle patches edit upstream's shipped JavaScript by exact match. What
 * has to hold is that they land once on the pristine release, heal the hybrid
 * states older patch generations left on devices, do nothing the second time,
 * and never touch a file whose shape they do not recognise.
 */
class CodeGraphPatchesTest {

    private fun lines(vararg lines: String) = lines.joinToString("\n")

    private val matcherSource = lines(
        "const LANGUAGE_FAMILY = {",
        "    typescript: 'web', tsx: 'web', javascript: 'web', jsx: 'web', arkts: 'web',",
        "    c: 'c', cpp: 'c',",
        "};",
        "function applyLanguageGate(candidates, ref) {",
        "    if (ref.referenceKind === 'references' || ref.referenceKind === 'function_ref') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    if (ref.referenceKind === 'imports') {",
        "        return candidates.filter((c) => !crossesKnownFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
        "}",
        "    // If only one match, use it — but penalize cross-language matches",
        "    if (candidates.length === 1) {",
        "        const isCrossLanguage = candidates[0].language !== ref.language;",
        "        return {",
        "            original: ref,",
        "            targetNodeId: candidates[0].id,",
        "            confidence: isCrossLanguage ? 0.5 : 0.9,",
        "            resolvedBy: 'exact-match',",
        "        };",
        "    }",
        "    // Prefer same-language matches",
        "    const sameLanguageCandidates = callableCandidates.filter(n => n.language === ref.language);",
        "    const finalCandidates = sameLanguageCandidates.length > 0 ? sameLanguageCandidates : callableCandidates;",
        "    if (finalCandidates.length === 1) {",
        "        const isCrossLanguage = finalCandidates[0].language !== ref.language;",
        "        return {",
        "            original: ref,",
        "            targetNodeId: finalCandidates[0].id,",
        "            confidence: isCrossLanguage ? 0.3 : 0.5,",
        "            resolvedBy: 'fuzzy',",
        "        };",
        "    }",
    )

    /** Generation 6/7 hybrid as found on devices: family line present, gates strict but unguarded. */
    private val matcherStaleSource = lines(
        "const LANGUAGE_FAMILY = {",
        "    typescript: 'web', tsx: 'web', javascript: 'web', jsx: 'web', arkts: 'web',",
        "    vue: 'web', svelte: 'web', astro: 'web',",
        "    c: 'c', cpp: 'c',",
        "};",
        "    if (ref.referenceKind === 'imports') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    // Harness patch: a coincidental same-named symbol in another language is",
        "    // not a call, extends, or instantiation.",
        "    if (ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
        "    // Harness patch: if only one match, strictly require same language family",
        "    if (candidates.length === 1) {",
        "        if (!sameLanguageFamily(candidates[0].language, ref.language)) {",
        "            return null;",
        "        }",
        "        return {",
        "            original: ref,",
        "            targetNodeId: candidates[0].id,",
        "            confidence: 0.9,",
        "            resolvedBy: 'exact-match',",
        "        };",
        "    }",
        "    // Harness patch: strictly same language family for fuzzy matching, never cross-language",
        "    const sameLanguageCandidates = callableCandidates.filter((n) => sameLanguageFamily(n.language, ref.language));",
        "    if (sameLanguageCandidates.length === 1) {",
        "        return {",
        "            original: ref,",
        "            targetNodeId: sameLanguageCandidates[0].id,",
        "            confidence: 0.5,",
        "            resolvedBy: 'fuzzy',",
        "        };",
        "    }",
        "    return null;",
    )

    private val resolverSource = lines(
        "    gateLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (!tgt || !ref.language)",
        "            return result;",
        "        if ((ref.referenceKind === 'references' || ref.referenceKind === 'function_ref') && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        if (ref.referenceKind === 'imports' && (0, name_matcher_1.crossesKnownFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
        "    }",
        "    gateFrameworkLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        if (ref.referenceKind !== 'references' && ref.referenceKind !== 'imports')",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (tgt && ref.language && (0, name_matcher_1.crossesKnownFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
        "    }",
        "    createEdges(resolved) {",
        "        return resolved.map((ref) => {",
        "            let kind = ref.original.referenceKind;",
        "            return {",
        "                source: ref.original.fromNodeId,",
        "                target: ref.targetNodeId,",
        "                kind,",
        "                line: ref.original.line,",
        "                metadata: {",
        "                    confidence: ref.confidence,",
        "                },",
        "            };",
        "        });",
        "    }",
    )

    /** Generation 2..6 hybrid gateLanguage as found on devices. */
    private val gateStaleSource = lines(
        "    gateLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (!tgt || !ref.language)",
        "            return result;",
        "        if ((ref.referenceKind === 'references' || ref.referenceKind === 'function_ref') && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        if (ref.referenceKind === 'imports' && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        // Harness patch: the candidate filter's rule, restated for the case",
        "        // where the foreign match was the only candidate there was.",
        "        if ((ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates') &&",
        "            !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
        "    }",
        "    gateFrameworkLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (!tgt || !ref.language)",
        "            return result;",
        "        // Harness patch: framework resolution must not bridge disparate languages for",
        "        // instantiations, extensions, references or imports.",
        "        if (ref.referenceKind === 'instantiates' || ref.referenceKind === 'extends') {",
        "            if (!(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "                return null;",
        "        }",
        "        if (ref.referenceKind === 'references' || ref.referenceKind === 'imports') {",
        "            if (!(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "                return null;",
        "        }",
        "        return result;",
        "    }",
    )

    /**
     * The hybrid createEdges a v7 generation left on devices: the loop
     * conversion landed but the pristine `return { … }` stayed inside the
     * loop, so the function returns a single edge object and every resolution
     * edge silently vanishes. The repair patch must heal exactly this shape.
     */
    private val createEdgesStaleSource = lines(
        "    createEdges(resolved) {",
        "        const out = [];",
        "        for (const ref of resolved) {",
        "            if (ref.original.fromNodeId === ref.targetNodeId) continue;",
        "            let kind = ref.original.referenceKind;",
        "            return {",
        "                source: ref.original.fromNodeId,",
        "                target: ref.targetNodeId,",
        "                kind,",
        "                line: ref.original.line,",
        "                metadata: {",
        "                    confidence: ref.confidence,",
        "                },",
        "            };",
        "            out.push(edge);",
        "            if (kind === 'imports') {",
        "                const fileNodes = [];",
        "                const fileNode = fileNodes.find((n) => n.kind === 'file');",
        "                if (fileNode) {",
        "                    out.push({ ...edge, source: fileNode.id });",
        "                }",
        "            }",
        "        }",
        "        return out;",
        "    }",
    )

    /** Generation 9 tree-sitter: the dual reference is in, on a renamed const. */
    private val treeSitterV9Source = lines(
        "    createNode(kind, name, node, extra) {",
        "        // Skip nodes with empty/missing names — they are not meaningful symbols",
        "        // and would cause FK violations when edges reference them (see issue #42)",
        "        if (!name) {",
        "            return null;",
        "        }",
        "        // Harness patch: filter out junk AST tokens that pollute symbol queries",
        "        if (name === '.' || name === '..' || name === '...' || name.startsWith('from ') || name.startsWith('import ')) {",
        "            return null;",
        "        }",
        "    }",
        "        if (this.extractor.extractImport) {",
        "            const info = this.extractor.extractImport(node, this.source);",
        "            if (info) {",
        "                const importNode = this.createNode('import', info.moduleName, node, {",
        "                    signature: info.signature,",
        "                });",
        "                if (!info.handledRefs && info.moduleName && this.nodeStack.length > 0) {",
        "                    const parentId = this.nodeStack[this.nodeStack.length - 1];",
        "                    if (parentId) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: parentId,",
        "                            referenceName: info.moduleName,",
        "                            referenceKind: 'imports',",
        "                            line: node.startPosition.row + 1,",
        "                            column: node.startPosition.column,",
        "                        });",
        "                    }",
        "                    if (importNode && importNode.id !== parentId) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: importNode.id,",
        "                            referenceName: info.moduleName,",
        "                            referenceKind: 'imports',",
        "                            line: node.startPosition.row + 1,",
        "                            column: node.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
        "                if (this.language === 'python' && node.type === 'import_statement') {",
        "                if (child?.type === 'dotted_name') {",
        "                    const impNode = this.createNode('import', (0, tree_sitter_helpers_1.getNodeText)(child, this.source), node, {",
        "                        signature: importText,",
        "                    });",
        "                    pushModuleRef(child);",
        "                    if (impNode) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: impNode.id,",
        "                            referenceName: (0, tree_sitter_helpers_1.getNodeText)(child, this.source),",
        "                            referenceKind: 'imports',",
        "                            line: child.startPosition.row + 1,",
        "                            column: child.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
        "            }",
        "        }",
    )

    /** Generation 9: the base is right, the wrapper above it emits the old model. */
    private val createEdgesV9Source = lines(
        "    createEdges(resolved) {",
        "        const mapped = this.createEdgesBase(resolved);",
        "        const out = [];",
        "        for (const edge of mapped) {",
        "            if (!edge || edge.source === edge.target) continue;",
        "            out.push(edge);",
        "            if (edge.kind === 'imports') {",
        "                const srcNode = this.queries.getNodeById(edge.source);",
        "                if (srcNode && srcNode.kind === 'import') {",
        "                    const fileNodes = this.context.getNodesInFile(srcNode.filePath);",
        "                    const fileNode = fileNodes ? fileNodes.find((n) => n.kind === 'file') : null;",
        "                    if (fileNode && fileNode.id !== edge.target) {",
        "                        out.push({ ...edge, source: fileNode.id });",
        "                    }",
        "                }",
        "            }",
        "        }",
        "        return out;",
        "    }",
        "    createEdgesBase(resolved) {",
        "        return resolved.map((ref) => {",
    )

    private val contextSource = lines(
        "            for (const sym of expandedSymbols) {",
        "                // Title-case the symbol: \"REST\" \u2192 \"Rest\", \"bulk\" \u2192 \"Bulk\", \"allocation\" \u2192 \"Allocation\"",
        "                const titleCased = sym.charAt(0).toUpperCase() + sym.slice(1).toLowerCase();",
        "                if (titleCased === sym)",
        "                    continue;",
        "            }",
        "                for (const term of searchTerms) {",
        "                    const termResults = this.queries.searchNodes(term, {",
        "                        limit: opts.searchLimit * 2,",
        "                        kinds: searchKinds,",
        "                    });",
        "                    for (const r of termResults) {",
        "                        const existing = termResultsMap.get(r.node.id);",
        "                        if (existing) {",
        "                            existing.termHits++;",
        "                            existing.result.score = Math.max(existing.result.score, r.score);",
        "                        }",
        "                        else {",
        "                            termResultsMap.set(r.node.id, { result: r, termHits: 1 });",
        "                        }",
        "                    }",
        "                }",
        "                textResults = Array.from(termResultsMap.values())",
        "                    .map(({ result, termHits }) => ({",
    )

    private val indexSource = lines(
        "        const db = db_1.DatabaseConnection.open(dbPath);",
        "        const queries = new queries_1.QueryBuilder(db.getDb());",
        "        const instance = new CodeGraph(db, queries, resolvedRoot);",
        "        let seedNames = options?.seedNames;",
        "        if (seedNames === undefined) {",
        "            try {",
        "                seedNames = this.getSegmentMatches((0, identifier_segments_1.extractSegmentSearchWords)(query), 8)",
        "                    .map((m) => m.name);",
        "            }",
        "        }",
    )

    private val extractionVersionSource = "exports.EXTRACTION_VERSION = 25;"

    private val importResolverSource = lines(
        "function getFileExportIndex(filePath, context) {",
        "    let idx = perFile.get(filePath);",
        "    if (!idx) {",
        "        idx = { byName: new Map(), defaultComponent: undefined, defaultFnClass: undefined };",
        "        for (const n of context.getNodesInFile(filePath)) {",
        "            if (!n.isExported)",
        "                continue;",
        "            if (!idx.byName.has(n.name))",
        "                idx.byName.set(n.name, n);",
        "            if (idx.defaultComponent === undefined && n.kind === 'component')",
        "                idx.defaultComponent = n;",
        "            if (idx.defaultFnClass === undefined && (n.kind === 'function' || n.kind === 'class'))",
        "                idx.defaultFnClass = n;",
        "        }",
        "        perFile.set(filePath, idx);",
        "    }",
        "    return idx;",
        "}",
        "function findExportedSymbolWalk(filePath, want, language, context, visited, depth) {",
        "    if (want.isDefault) {",
        "        const direct = exportIndex.defaultComponent ?? exportIndex.defaultFnClass;",
        "        if (direct)",
        "            return direct;",
        "    }",
        "    else if (want.isNamespace && want.memberName) {",
        "        const direct = exportIndex.byName.get(want.memberName);",
        "        if (direct)",
        "            return direct;",
        "    }",
        "    return undefined;",
        "}",
        "function extractJSImports(content) {",
        "    const mappings = [];",
        "    const requireRegex = /(?:const|let|var)\\s+(?:(\\w+)|{([^}]+)})\\s*=\\s*require\\(['\"]([^'\"]+)['\"]\\)/g;",
        "    while ((match = requireRegex.exec(content)) !== null) {",
        "        const [, defaultName, destructured, source] = match;",
        "        if (defaultName) {",
        "            mappings.push({",
        "                localName: defaultName,",
        "                exportedName: 'default',",
        "                source: source,",
        "                isDefault: true,",
        "                isNamespace: false,",
        "            });",
        "        }",
        "    }",
        "    return mappings;",
        "}",
        "function resolveModuleImportToFile(ref, imports, context) {",
        "    if (ref.referenceKind !== 'imports')",
        "        return null;",
        "    if (ref.referenceName.includes('.'))",
        "        return null;",
        "    for (const imp of imports) {",
        "        if (imp.localName !== ref.referenceName)",
        "            continue;",
        "        let modulePath;",
        "        if (imp.isNamespace || imp.isDefault) {",
        "            modulePath = imp.source;",
        "        }",
        "        return null;",
        "    }",
        "    return null;",
        "}",
        "function resolvePythonAbsoluteModule(ref, context) {",
        "    if (ref.referenceKind !== 'imports')",
        "        return null;",
        "    // Only a DOTTED `import a.b.c` ref carries its full module path. A bare leaf",
        "    // (`from app.api.routes import authentication`) is ambiguous on its own — three",
        "    // `authentication.py` files may exist — so leave it to resolveModuleImportToFile,",
        "    // which uses the import's source (`app.api.routes`) to build the full path.",
        "    if (!ref.referenceName.includes('.'))",
        "        return null;",
        "    const hit = findPythonModuleFile(ref.referenceName, context, ref.filePath);",
        "    return hit ? { original: ref, targetNodeId: hit.id, confidence: 0.9, resolvedBy: 'import' } : null;",
        "}",
    )

    private val treeSitterSource = lines(
        "    createNode(kind, name, node, extra) {",
        "        // Skip nodes with empty/missing names — they are not meaningful symbols",
        "        // and would cause FK violations when edges reference them (see issue #42)",
        "        if (!name) {",
        "            return null;",
        "        }",
        "        const id = generateNodeId();",
        "        return { id, name };",
        "    }",
        "        if (this.extractor.extractImport) {",
        "            const info = this.extractor.extractImport(node, this.source);",
        "            if (info) {",
        "                this.createNode('import', info.moduleName, node, {",
        "                    signature: info.signature,",
        "                });",
        "                // Create unresolved reference unless the hook handled it",
        "                if (!info.handledRefs && info.moduleName && this.nodeStack.length > 0) {",
        "                    const parentId = this.nodeStack[this.nodeStack.length - 1];",
        "                    if (parentId) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: parentId,",
        "                            referenceName: info.moduleName,",
        "                            referenceKind: 'imports',",
        "                            line: node.startPosition.row + 1,",
        "                            column: node.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
        "                if (this.language === 'python' && node.type === 'import_statement') {",
        "                if (child?.type === 'dotted_name') {",
        "                    this.createNode('import', (0, tree_sitter_helpers_1.getNodeText)(child, this.source), node, {",
        "                        signature: importText,",
        "                    });",
        "                    pushModuleRef(child);",
        "                }",
    )

    /** Generation 7 hybrid: junk filter and impNode ref already applied; only the hook is stale. */
    private val treeSitterStaleSource = lines(
        "    createNode(kind, name, node, extra) {",
        "        // Skip nodes with empty/missing names — they are not meaningful symbols",
        "        // and would cause FK violations when edges reference them (see issue #42)",
        "        if (!name) {",
        "            return null;",
        "        }",
        "        // Harness patch: filter out junk AST tokens that pollute symbol queries",
        "        if (name === '.' || name === '..' || name === '...' || name.startsWith('from ') || name.startsWith('import ')) {",
        "            return null;",
        "        }",
        "        if (this.extractor.extractImport) {",
        "            const info = this.extractor.extractImport(node, this.source);",
        "            if (info) {",
        "                const importNode = this.createNode('import', info.moduleName, node, {",
        "                    signature: info.signature,",
        "                });",
        "                // Create unresolved reference attached to the import node",
        "                if (!info.handledRefs && info.moduleName && this.nodeStack.length > 0) {",
        "                    const parentId = this.nodeStack[this.nodeStack.length - 1];",
        "                    const fromId = importNode ? importNode.id : parentId;",
        "                    if (fromId) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: fromId,",
        "                            referenceName: info.moduleName,",
        "                            referenceKind: 'imports',",
        "                            line: node.startPosition.row + 1,",
        "                            column: node.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
        "                if (this.language === 'python' && node.type === 'import_statement') {",
        "                if (child?.type === 'dotted_name') {",
        "                    const impNode = this.createNode('import', (0, tree_sitter_helpers_1.getNodeText)(child, this.source), node, {",
        "                        signature: importText,",
        "                    });",
        "                    pushModuleRef(child);",
        "                    if (impNode) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: impNode.id,",
        "                            referenceName: (0, tree_sitter_helpers_1.getNodeText)(child, this.source),",
        "                            referenceKind: 'imports',",
        "                            line: child.startPosition.row + 1,",
        "                            column: child.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
    )

    private val toolsSource = lines(
        "        .replace(/\\b([A-Za-z_][\\w@]*)\\/(\\d{1,3})(?=$|[\\s,()[\\]/])/g, '$1')",
        "    CLIFF_FRACTION: 0.15,",
        "    if (fileCount < 150) {",
        "        return {",
        "            // ITER3: revert iter2's aggressive body shrink (forced Read fallback —",
        "            // the per-file 2.5K cap pushed the agent to Read instead of node).",
        "            // Back to the iter1 shape (13K/4/3.8K) but keep the test-file",
        "            // hard-exclude. The cost lever for this tier lives in steering the",
        "            // agent to stop after 1-2 calls, not in this budget.",
        "            maxOutputChars: 13000,",
        "            defaultMaxFiles: 4,",
        "            maxCharsPerFile: 3800,",
        "            gapThreshold: 7,",
        "            maxSymbolsInFileHeader: 5,",
        "            maxEdgesPerRelationshipKind: 4,",
        "            includeRelationships: false,",
        "            includeAdditionalFiles: false,",
        "            includeCompletenessSignal: false,",
        "            includeBudgetNote: false,",
        "        };",
        "    }",
        "        const hardCeiling = Math.min(Math.round(budget.maxOutputChars * 1.5), 25000);",
        "        const subgraph = await cg.findRelevantContext(matchQuery, {",
        "            searchLimit: 8,",
        "            traversalDepth: 3,",
        "            maxNodes: 200,",
        "            minScore: 0.2,",
        "        });",
        "        let summaryLine = survivors.length > 0",
        "            ? `Found \${shownSymbols} symbol\${shownSymbols === 1 ? '' : 's'} across \${survivors.length} file\${survivors.length === 1 ? '' : 's'}.`",
        "            : `Found \${subgraph.nodes.size} symbol\${subgraph.nodes.size === 1 ? '' : 's'} across \${fileGroups.size} file\${fileGroups.size === 1 ? '' : 's'}.`;",
    )

    /** Generation 3/4 hybrid: normalize, cliff, summary already applied; budget and searchLimit stale. */
    private val toolsStaleSource = lines(
        "        .replace(/(?<![\\w/])([A-Za-z_][\\w@]*)\\/(\\d{1,3})(?=$|[\\s,()[\\]])/g, '$1')",
        "    CLIFF_FRACTION: 0.05,",
        "    if (fileCount < 150) {",
        "        return {",
        "            maxOutputChars: 24000,",
        "            defaultMaxFiles: 8,",
        "            maxCharsPerFile: 6500,",
        "            gapThreshold: 7,",
        "            maxSymbolsInFileHeader: 12,",
        "            maxEdgesPerRelationshipKind: 4,",
        "            includeRelationships: false,",
        "            includeAdditionalFiles: false,",
        "            includeCompletenessSignal: false,",
        "            includeBudgetNote: false,",
        "        };",
        "    }",
        "        const hardCeiling = Math.min(Math.round(budget.maxOutputChars * 1.5), 25000);",
        "        const subgraph = await cg.findRelevantContext(matchQuery, {",
        "            searchLimit: Math.max(24, maxFiles * 2),",
        "            traversalDepth: 3,",
        "            maxNodes: 200,",
        "            minScore: 0.2,",
        "        });",
        "        const totalFound = subgraph.nodes.size;",
        "        const countNote = (survivors.length > 0 && totalFound > shownSymbols) ? ` (showing \${shownSymbols} of \${totalFound})` : '';",
        "        let summaryLine = survivors.length > 0",
        "            ? `Found \${shownSymbols} symbol\${shownSymbols === 1 ? '' : 's'} across \${survivors.length} file\${survivors.length === 1 ? '' : 's'}.`",
        "            : `Found \${subgraph.nodes.size} symbol\${subgraph.nodes.size === 1 ? '' : 's'} across \${fileGroups.size} file\${fileGroups.size === 1 ? '' : 's'}.`;",
    )

    private val binSource = lines(
        "            else {",
        "                console.log(chalk.bold(`\\nImpact of changing \"\${symbol}\" — \${mergedNodes.size} affected symbols:\\n`));",
        "            }",
    )

    private val dbSource = lines(
        "    db.pragma('busy_timeout = 5000'); // MUST be first — see above",
        "    db.pragma('synchronous = NORMAL'); // safe with WAL mode",
        "        if (currentVersion < migrations_1.CURRENT_SCHEMA_VERSION) {",
        "            (0, migrations_1.runMigrations)(db, currentVersion);",
        "        }",
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize']);",
        "    close() {",
        "        this.db.close();",
        "    }",
    )

    /** Generation 4 hybrid maintenance as found on devices. */
    private val dbStaleSource = lines(
        "    db.pragma('busy_timeout = 5000'); // MUST be first — see above",
        "    db.pragma('synchronous = NORMAL'); // safe with WAL mode",
        "        if (currentVersion < migrations_1.CURRENT_SCHEMA_VERSION) {",
        "            (0, migrations_1.runMigrations)(db, currentVersion);",
        "        }",
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE']);",
        "    close() {",
        "        this.db.close();",
        "    }",
    )

    private val migrationsSource = lines(
        "exports.CURRENT_SCHEMA_VERSION = 9;",
        "const migrations = [",
        "    {",
        "        version: 9,",
        "        description: 'test',",
        "        up: (db) => {",
        "            db.exec('CREATE INDEX IF NOT EXISTS idx_files_generated ON files(path) WHERE generated = 1');",
        "        },",
        "    },",
        "];",
    )

    /**
     * Generation 9: the freelist patch is in, migration 11 and all, which is
     * what makes it dangerous — the migration vacuums inside the transaction
     * migrations run in, so every sync fails until the body is rewritten.
     */
    private val migrationsV9Source = lines(
        "exports.CURRENT_SCHEMA_VERSION = 11;",
        "const migrations = [",
        "    {",
        "        version: 10,",
        "        description: 'Prune cross-language edges',",
        "        up: (db) => {",
        "            db.exec(`",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "        UPDATE project_metadata SET value = '26' WHERE key = 'indexed_with_extraction_version';",
        "      `);",
        "        },",
        "    },",
        "    {",
        "        version: 11,",
        "        description: 'Compact the database freelist after the cross-language prune',",
        "        up: (db) => {",
        "            db.exec(`",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "        UPDATE project_metadata SET value = '26' WHERE key = 'indexed_with_extraction_version';",
        "        PRAGMA auto_vacuum = INCREMENTAL;",
        "        VACUUM;",
        "      `);",
        "        },",
        "    },",
        "];",
    )

    /** Generation 4..8 hybrid: migration 10 without the metadata update, no migration 11. */
    private val migrationsStaleSource = lines(
        "exports.CURRENT_SCHEMA_VERSION = 11;",
        "const migrations = [",
        "    {",
        "        version: 10,",
        "        description: 'Prune cross-language edges, unique unresolved refs index, and prune vocab orphans',",
        "        up: (db) => {",
        "            db.exec(`",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "      `);",
        "        },",
        "    },",
        "];",
    )

    private val queriesSource = lines(
        "this.runBatched('insertUnresolvedRefs', 'INSERT INTO unresolved_refs (from_node_id, reference_name, reference_kind, line, col, candidates, file_path, language) VALUES ', '(?,?,?,?,?,?,?,?)', rows);",
        "    deleteNodesByFile(filePath) {",
        "        if (!this.stmts.deleteNodesByFile) {",
        "            this.stmts.deleteNodesByFile = this.db.prepare('DELETE FROM nodes WHERE file_path = ?');",
        "        }",
        "        // Invalidate cache for nodes in this file",
        "        for (const [id, node] of this.nodeCache) {",
        "            if (node.filePath === filePath) {",
        "                this.nodeCache.delete(id);",
        "            }",
        "        }",
        "        this.stmts.deleteNodesByFile.run(filePath);",
        "    }",
    )

    /** Generation 6/7 hybrid: INSERT OR IGNORE and vocab prune applied; method missing. */
    private val queriesStaleSource = lines(
        "this.runBatched('insertUnresolvedRefs', 'INSERT OR IGNORE INTO unresolved_refs (from_node_id, reference_name, reference_kind, line, col, candidates, file_path, language) VALUES ', '(?,?,?,?,?,?,?,?)', rows);",
        "    deleteNodesByFile(filePath) {",
        "        if (!this.stmts.deleteNodesByFile) {",
        "            this.stmts.deleteNodesByFile = this.db.prepare('DELETE FROM nodes WHERE file_path = ?');",
        "        }",
        "        // Invalidate cache for nodes in this file",
        "        for (const [id, node] of this.nodeCache) {",
        "            if (node.filePath === filePath) {",
        "                this.nodeCache.delete(id);",
        "            }",
        "        }",
        "        this.stmts.deleteNodesByFile.run(filePath);",
        "        try {",
        "            this.db.exec('DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes)');",
        "        } catch { /* ignore */ }",
        "    }",
    )

    private fun tempDir(): File {
        val dir = File.createTempFile("cgpatch", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private fun bundle(
        matcher: String = matcherSource,
        resolver: String = resolverSource,
        treeSitter: String = treeSitterSource,
        tools: String = toolsSource,
        db: String = dbSource,
        migrations: String = migrationsSource,
        queries: String = queriesSource,
        context: String = contextSource,
    ): File {
        val dist = tempDir()
        File(dist, "resolution").mkdirs()
        File(dist, "extraction").mkdirs()
        File(dist, "mcp").mkdirs()
        File(dist, "bin").mkdirs()
        File(dist, "db").mkdirs()
        File(dist, "context").mkdirs()
        File(dist, "index.js").writeText(indexSource)
        File(dist, "context/index.js").writeText(context)
        File(dist, "resolution/name-matcher.js").writeText(matcher)
        File(dist, "resolution/index.js").writeText(resolver)
        File(dist, "resolution/import-resolver.js").writeText(importResolverSource)
        File(dist, "extraction/tree-sitter.js").writeText(treeSitter)
        File(dist, "extraction/extraction-version.js").writeText(extractionVersionSource)
        File(dist, "mcp/tools.js").writeText(tools)
        File(dist, "bin/codegraph.js").writeText(binSource)
        File(dist, "db/index.js").writeText(db)
        File(dist, "db/migrations.js").writeText(migrations)
        File(dist, "db/queries.js").writeText(queries)
        return dist
    }

    @Test
    fun `applies all bundle patches to a pristine release`() {
        val dist = bundle()

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("anchors must match the shipped shape: ${result.unresolved}", result.unresolved.isEmpty())
        assertEquals("every patched file should be touched", CodeGraphBundlePatches.patchedFiles.size, result.applied.toSet().size)

        val matcher = File(dist, "resolution/name-matcher.js").readText()
        val resolver = File(dist, "resolution/index.js").readText()
        val index = File(dist, "index.js").readText()
        val importResolver = File(dist, "resolution/import-resolver.js").readText()
        val treeSitter = File(dist, "extraction/tree-sitter.js").readText()
        val extractionVersion = File(dist, "extraction/extraction-version.js").readText()
        val tools = File(dist, "mcp/tools.js").readText()
        val bin = File(dist, "bin/codegraph.js").readText()
        val db = File(dist, "db/index.js").readText()
        val migrations = File(dist, "db/migrations.js").readText()
        val queries = File(dist, "db/queries.js").readText()

        assertTrue("name-matcher includes web SFCs", matcher.contains("vue: 'web', svelte: 'web', astro: 'web'"))
        assertTrue("name-matcher gates candidates by family with guard", matcher.contains("if (!ref.language) return candidates;"))
        assertTrue("name-matcher single exact match guarded", matcher.contains("ref.language && !sameLanguageFamily"))
        assertTrue("name-matcher fuzzy guarded", matcher.contains("sameLanguageCandidates = ref.language"))
        assertTrue("resolver drops self-loops and falls back to node language", resolver.contains("const refLang = ref.language || this.getLanguageFromNodeId(ref.fromNodeId);"))
        assertTrue("framework gate rejects cross-language decorates", resolver.contains("ref.referenceKind === 'decorates'"))
        assertTrue("createEdges wrapper folds imports onto files", resolver.contains("createEdgesBase(resolved) {") && resolver.contains("const from = src.kind === 'file' ? src : this.fileNodeOf(src.filePath);") && resolver.contains("out.push({ ...edge, source: from.id, target: to.id });"))
        assertTrue("createEdges wrapper dedupes imports", resolver.contains("seenImports.has(key)"))
        assertTrue("createEdges wrapper drops self-loops", resolver.contains("edge.source === edge.target"))
        assertTrue("index.js performs retroactive prune on open", index.contains("pruneCrossLanguageEdges()"))
        assertTrue("import resolver supports bare python module import", importResolver.contains("bare single module imports"))
        assertTrue("import resolver matches TS relative imports", importResolver.contains("imp.source === ref.referenceName"))
        // CommonJS: a require binding must also be a namespace, the export index
        // must carry the module's declared exports, and the member lookup must
        // consult it. Each is useless without the others.
        assertTrue("require binding maps as a namespace too", importResolver.contains("exportedName: '*',"))
        assertTrue("export index carries declared CommonJS exports", importResolver.contains("cjsByName: null"))
        assertTrue("export index records CommonJS property names", importResolver.contains("idx.cjsByName.set(property, node);"))
        assertTrue("member lookup consults CommonJS exports", importResolver.contains("exportIndex.cjsByName?.get(want.memberName)"))
        assertTrue("tree-sitter rejects junk AST names", treeSitter.contains("name.startsWith('from ')"))
        assertTrue("tree-sitter emits refs from file AND import node", treeSitter.contains("importNode.id !== parentId"))
        assertTrue("extraction version bumped to 26", extractionVersion.contains("EXTRACTION_VERSION = 26;"))
        assertTrue("normalizeQuery preserves storage path", tools.contains("(?<![\\w/])"))
        assertTrue("mcp explore shows truncation note", tools.contains("showing \${shownSymbols} of \${totalFound}"))
        assertTrue("mcp explore relaxes cliff fraction", tools.contains("CLIFF_FRACTION: 0.05"))
        assertTrue("mcp explore raises searchLimit", tools.contains("Math.max(30, maxFiles * 3)"))
        assertTrue("mcp explore raises base output budget", tools.contains("maxOutputChars: 40000"))
        assertTrue("mcp explore raises hard ceiling", tools.contains("40000);"))
        assertTrue("bin impact shows multi-def note", bin.contains("definitions named"))
        assertTrue("db maintenance excludes virtual FTS table", db.contains("ANALYZE nodes"))
        assertTrue("db enables incremental auto-vacuum", db.contains("db.pragma('auto_vacuum = INCREMENTAL');"))
        assertTrue("db reclaims space outside any transaction", db.contains("reclaimSpace() {") && db.contains("this.db.exec('VACUUM');"))
        assertTrue("db calls reclaimSpace after migrations commit", db.contains("conn.reclaimSpace();"))
        assertTrue("migrations bumps version to 11", migrations.contains("CURRENT_SCHEMA_VERSION = 11;"))
        assertTrue("migrations includes version 11", migrations.contains("version: 11,"))
        // The migration transaction is why the freelist is reclaimed from
        // reclaimSpace instead: VACUUM inside it fails on every sync.
        assertTrue("migration 11 never vacuums", !migrations.contains("VACUUM;"))
        assertTrue("migration 11 folds legacy import edges onto files", migrations.contains("UPDATE OR IGNORE edges SET target"))
        assertTrue("queries uses INSERT OR IGNORE", queries.contains("INSERT OR IGNORE INTO unresolved_refs"))
        assertTrue("queries has pruneCrossLanguageEdges method", queries.contains("pruneCrossLanguageEdges()"))
        assertTrue("queries prunes vocab on file deletion", queries.contains("DELETE FROM name_segment_vocab WHERE name NOT IN"))
    }

    @Test
    fun `heals the hybrid states older generations left on devices`() {
        val dist = bundle(
            matcher = matcherStaleSource,
            resolver = gateStaleSource + "\n" + createEdgesStaleSource,
            treeSitter = treeSitterStaleSource,
            tools = toolsStaleSource,
            db = dbStaleSource,
            migrations = migrationsStaleSource,
            queries = queriesStaleSource,
        )

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("every hybrid state must heal: ${result.unresolved}", result.unresolved.isEmpty())

        val matcher = File(dist, "resolution/name-matcher.js").readText()
        val resolver = File(dist, "resolution/index.js").readText()
        val treeSitter = File(dist, "extraction/tree-sitter.js").readText()
        val tools = File(dist, "mcp/tools.js").readText()
        val db = File(dist, "db/index.js").readText()
        val migrations = File(dist, "db/migrations.js").readText()
        val queries = File(dist, "db/queries.js").readText()

        assertTrue("matcher healed to guarded gate", matcher.contains("ref.referenceKind === 'decorates'") && matcher.contains("if (!ref.language) return candidates;"))
        assertTrue("matcher exact single guarded", matcher.contains("ref.language && !sameLanguageFamily"))
        assertTrue("matcher fuzzy guarded", matcher.contains("sameLanguageCandidates = ref.language"))
        assertTrue("gateLanguage healed to self-loop + language fallback", resolver.contains("const refLang = ref.language || this.getLanguageFromNodeId(ref.fromNodeId);"))
        assertTrue("createEdges repair converted the stray return", resolver.contains("const edge = {") && resolver.contains("out.push(edge);") && !resolver.contains("            return {\n                source: ref.original.fromNodeId,"))
        assertTrue("createEdges repair keeps the loop returning the array", resolver.contains("return out;"))
        assertTrue("createEdges wrapper folded the loop shape too", resolver.contains("const from = src.kind === 'file' ? src : this.fileNodeOf(src.filePath);"))
        assertTrue("tree-sitter healed to dual refs", treeSitter.contains("importNode.id !== parentId"))
        assertTrue("tools budget healed", tools.contains("maxOutputChars: 40000"))
        assertTrue("tools searchLimit healed", tools.contains("Math.max(30, maxFiles * 3)"))
        assertTrue("db maintenance healed", db.contains("ANALYZE nodes"))
        assertTrue("db healed to reclaim space on open", db.contains("reclaimSpace() {") && db.contains("conn.reclaimSpace();"))
        assertTrue("migrations healed with migration 11", migrations.contains("version: 11,") && migrations.contains("UPDATE OR IGNORE edges SET target"))
        assertTrue("healed migration 11 does not vacuum", !migrations.contains("VACUUM;"))
        assertTrue("queries healed with prune method", queries.contains("pruneCrossLanguageEdges() {"))
    }

    /**
     * The state a device reaches by installing one build after another: the
     * freelist patch is in with its migration 11, which is what has to come
     * out, and the dual import reference is in under a shape the pristine
     * anchor no longer matches. Both have to be recognised, not re-applied and
     * not reported as unresolved.
     */
    @Test
    fun `heals a generation 9 device without leaving the vacuum in place`() {
        val dist = bundle(
            resolver = gateStaleSource + "\n" + createEdgesV9Source,
            treeSitter = treeSitterV9Source,
            migrations = migrationsV9Source,
        )

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("generation 9 must heal cleanly: ${result.unresolved}", result.unresolved.isEmpty())
        assertTrue(
            "the migration and the edge model are what change: ${result.applied}",
            result.applied.contains("db/migrations.js") &&
                result.applied.contains("resolution/index.js") &&
                result.applied.contains("db/index.js"),
        )
        assertTrue(
            "a tree-sitter file that already emits the dual reference is done",
            !result.applied.contains("extraction/tree-sitter.js"),
        )

        val resolver = File(dist, "resolution/index.js").readText()
        assertTrue("the old import model must be replaced", resolver.contains("fileNodeOf(filePath)"))
        assertTrue("the mirrored import edge must be gone", !resolver.contains("out.push({ ...edge, source: fileNode.id });"))
        assertTrue("the base must not be wrapped by itself", resolver.split("createEdgesBase(resolved) {").size - 1 == 1)
        assertTrue("the base must still be there", resolver.contains("return resolved.map((ref) => {"))

        val migrations = File(dist, "db/migrations.js").readText()
        assertTrue("the vacuum must be gone from the migration", !migrations.contains("VACUUM;"))
        assertTrue("legacy import edges must be folded onto files", migrations.contains("UPDATE OR IGNORE edges SET target"))
        assertTrue("migration 11 must survive", migrations.contains("version: 11,"))
    }

    @Test
    fun `applying twice changes nothing the second time`() {
        val dist = bundle()
        CodeGraphBundlePatches.apply(dist)
        val afterFirst = File(dist, "resolution/index.js").readText()

        val second = CodeGraphBundlePatches.apply(dist)

        assertTrue("a patched file must not be patched again", second.applied.isEmpty())
        assertTrue("nothing may be unresolved either: ${second.unresolved}", second.unresolved.isEmpty())
        assertEquals("the file must be byte for byte the same", afterFirst, File(dist, "resolution/index.js").readText())
    }

    @Test
    fun `an unrecognised bundle is reported and left alone`() {
        val dist = tempDir()
        File(dist, "resolution").mkdirs()
        val matcher = File(dist, "resolution/name-matcher.js")
        val other = "function applyLanguageGate(candidates, ref) { return candidates; }\n"
        matcher.writeText(other)

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("nothing should be applied", result.applied.isEmpty())
        assertTrue("unresolved list must contain name-matcher", result.unresolved.contains("resolution/name-matcher.js"))
        assertEquals("an unmatched file must be untouched", other, matcher.readText())
    }

    /**
     * The fixtures above pin the shapes we have seen on devices. This one asks
     * the opposite question: do the anchors still match the release they are
     * written against? Set `CODEGRAPH_RELEASE_DIST` to the unpacked `lib/dist`
     * of a release archive to run it; the patched copy is left in the temp dir
     * so the result can be run, not just counted.
     */
    @Test
    fun `applies to a real release bundle when one is provided`() {
        val source = System.getenv("CODEGRAPH_RELEASE_DIST")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val dist = File(System.getProperty("java.io.tmpdir"), "codegraph-patched-dist")
        dist.deleteRecursively()
        source.copyRecursively(dist, overwrite = true)

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("every anchor must still match the bundle: ${result.unresolved}", result.unresolved.isEmpty())
        // Not every file changes on a bundle a previous generation already
        // patched, so the count is asserted only for a pristine release, which
        // the fixture test above covers. What has to hold anywhere is that
        // nothing is left unrecognised and a second pass does nothing.
        assertTrue("a second pass must be a no-op", CodeGraphBundlePatches.apply(dist).applied.isEmpty())
    }
}
