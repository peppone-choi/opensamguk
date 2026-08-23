import fs from 'node:fs/promises';
import path from 'node:path';

// ---------------------------------------------------------------------------
// PORT of legacy/devsam-core2026/tools/compare-command-logs.mjs (P2 GS1).
//
//   * PHP_ROOT re-pointed legacy/hwe/sammo/Command  →  legacy/devsam-core/hwe/sammo/Command
//     (opt-in frozen historical PHP evidence; ADR-LITE-042; not current product authority).
//   * The PHP action-log extractor + `normalizeTemplate` + the guard/target excludes +
//     the report shape are KEPT VERBATIM from the legacy tool.
//   * The TS source extractor is REPLACED by a dependency-free Kotlin source extractor
//     that scans `logic/src/main/kotlin/opensamguk/logic/actions` (static source scan, no
//     build, no `typescript` parser dep) and mirrors the same normalizer.
//   * The matched set is SCOPED to the committed PHP-captured goldens
//     (`logic/src/test/resources/golden/p2/*.json` — 28 commands). The 12 backlogged
//     commands (tools/php-golden/p2-capture-backlog.md) + the ~57-of-93 non-P2 commands
//     are on the documented ignore-list (compare-command-logs.ignore.json). The GS1 gate
//     is: mismatches == 0 over the captured set, and matched-count rises monotonically.
//
// Per-command key = bare command short name (`che_증여`, `cr_맹훈련`) — the General/Nation
// directory prefix is stripped on BOTH sides so PHP ↔ Kotlin ↔ golden-filename all align.
// ---------------------------------------------------------------------------

import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
// repo root = tools/php-golden/compare-command-logs/ → ../../../
const ROOT_DIR = path.resolve(HERE, '..', '..', '..');
const PHP_ROOT = path.join(ROOT_DIR, 'legacy', 'devsam-core', 'hwe', 'sammo', 'Command');
const KOTLIN_ROOT = path.join(ROOT_DIR, 'logic', 'src', 'main', 'kotlin', 'opensamguk', 'logic', 'actions');
const GOLDEN_DIR = path.join(ROOT_DIR, 'logic', 'src', 'test', 'resources', 'golden', 'p2');

const DEFAULT_MODE = 'action';
const DEFAULT_EXCLUDE_GUARDS = true;
const DEFAULT_EXCLUDE_TARGET = true;
const DEFAULT_IGNORE_FILE = path.join('tools', 'php-golden', 'compare-command-logs', 'compare-command-logs.ignore.json');

const ARG_HELP = `
Usage: node tools/php-golden/compare-command-logs/compare-command-logs.mjs [options]

Compares per-command action-log templates between the frozen historical PHP baseline (ADR-LITE-042; not current product authority)
(legacy/devsam-core/hwe/sammo/Command) and the Kotlin resolvers
(logic/src/main/kotlin/opensamguk/logic/actions). The matched set is scoped to the
committed PHP-captured goldens (logic/src/test/resources/golden/p2/*.json) unless
--all-commands is passed.

Options:
    --mode action|history|all   Compare action logs (default), history logs, or all logs.
    --include <regex>           Only include command keys matching regex.
    --include-guards            Include guard/invalid-state logs (default: excluded).
    --include-target            Include target/broadcast logs (default: excluded).
    --count-sensitive           Compare duplicated template counts (default: off).
    --strict                    Compare raw templates without normalization.
    --keep-date                 Keep <1>...</> date markers in normalized output.
    --all-commands              Compare ALL Kotlin/PHP command keys, not just the captured set.
    --ignore-file <path>        JSON ignore list file (default: tools/php-golden/compare-command-logs/compare-command-logs.ignore.json).
    --checklist                 Output a markdown checklist for mismatches.
    --json                      Output JSON report.
    --gate                      Exit non-zero unless mismatches == 0 over the captured set.
    --help                      Show this help.
`;

const args = process.argv.slice(2);
const modeArgIndex = args.indexOf('--mode');
const mode = modeArgIndex >= 0 ? args[modeArgIndex + 1] : DEFAULT_MODE;
const includeIndex = args.indexOf('--include');
const includePattern = includeIndex >= 0 ? args[includeIndex + 1] : null;
const strict = args.includes('--strict');
const keepDate = args.includes('--keep-date');
const asJson = args.includes('--json');
const includeGuards = args.includes('--include-guards');
const includeTarget = args.includes('--include-target');
const countSensitive = args.includes('--count-sensitive');
const checklist = args.includes('--checklist');
const allCommands = args.includes('--all-commands');
const gate = args.includes('--gate');
const ignoreFileIndex = args.indexOf('--ignore-file');
const ignoreFile = ignoreFileIndex >= 0 ? args[ignoreFileIndex + 1] : DEFAULT_IGNORE_FILE;

if (args.includes('--help')) {
    console.log(ARG_HELP.trim());
    process.exit(0);
}

if (!['action', 'history', 'all'].includes(mode)) {
    console.error(`Invalid --mode ${mode}`);
    console.error(ARG_HELP.trim());
    process.exit(1);
}

const includeRegex = includePattern ? new RegExp(includePattern) : null;

// ===========================================================================
// KEPT VERBATIM from the legacy tool: guard patterns, file walk, PHP extractor,
// PHP-string-literal/assignment resolution, normalizeTemplate, ignore rules,
// mode filter, report builder, checklist.
// ===========================================================================

const guardPatterns = [
    /정보를 찾지 못했습니다/,
    /정보가 없습니다/,
    /병종 정보를 확인할 수 없어/,
    /현재 선택할 수 없는 병종입니다/,
    /도시 정보가 없어/,
];

const excludeGuards = DEFAULT_EXCLUDE_GUARDS && !includeGuards;
const excludeTarget = DEFAULT_EXCLUDE_TARGET && !includeTarget;

const collectFiles = async (dir) => {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    const files = [];
    for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            files.push(...(await collectFiles(fullPath)));
        } else {
            files.push(fullPath);
        }
    }
    return files;
};

const buildLineIndex = (text) => {
    const lineStarts = [0];
    for (let i = 0; i < text.length; i += 1) {
        if (text[i] === '\n') {
            lineStarts.push(i + 1);
        }
    }
    return lineStarts;
};

const getLineNumber = (lineStarts, index) => {
    let low = 0;
    let high = lineStarts.length - 1;
    while (low <= high) {
        const mid = Math.floor((low + high) / 2);
        if (lineStarts[mid] <= index && (mid === lineStarts.length - 1 || lineStarts[mid + 1] > index)) {
            return mid + 1;
        }
        if (lineStarts[mid] > index) {
            high = mid - 1;
        } else {
            low = mid + 1;
        }
    }
    return 1;
};

const scanToDelimiter = (text, startIndex, delimiter) => {
    let i = startIndex;
    let depth = 0;
    let quote = null;
    let escaped = false;
    while (i < text.length) {
        const ch = text[i];
        if (quote) {
            if (escaped) {
                escaped = false;
            } else if (ch === '\\') {
                escaped = true;
            } else if (ch === quote) {
                quote = null;
            }
            i += 1;
            continue;
        }

        if (ch === '\'' || ch === '"') {
            quote = ch;
            i += 1;
            continue;
        }

        if (ch === '(' || ch === '[' || ch === '{') {
            depth += 1;
        } else if (ch === ')' || ch === ']' || ch === '}') {
            if (depth > 0) {
                depth -= 1;
            }
        } else if (ch === delimiter && depth === 0) {
            return { value: text.slice(startIndex, i), endIndex: i + 1 };
        }
        i += 1;
    }
    return { value: text.slice(startIndex), endIndex: text.length };
};

const scanToFirstArgumentEnd = (text, startIndex) => {
    let i = startIndex;
    let depth = 0;
    let quote = null;
    let escaped = false;
    while (i < text.length) {
        const ch = text[i];
        if (quote) {
            if (escaped) {
                escaped = false;
            } else if (ch === '\\') {
                escaped = true;
            } else if (ch === quote) {
                quote = null;
            }
            i += 1;
            continue;
        }

        if (ch === '\'' || ch === '"') {
            quote = ch;
            i += 1;
            continue;
        }

        if (ch === '(' || ch === '[' || ch === '{') {
            depth += 1;
        } else if (ch === ')' || ch === ']' || ch === '}') {
            if (depth > 0) {
                depth -= 1;
            } else if (ch === ')') {
                return { value: text.slice(startIndex, i), endIndex: i + 1, endedBy: ')' };
            }
        } else if (ch === ',' && depth === 0) {
            return { value: text.slice(startIndex, i), endIndex: i + 1, endedBy: ',' };
        }

        i += 1;
    }
    return { value: text.slice(startIndex), endIndex: text.length, endedBy: null };
};

const scanToParenEnd = (text, startIndex) => {
    let i = startIndex;
    let depth = 0;
    let quote = null;
    let escaped = false;
    while (i < text.length) {
        const ch = text[i];
        if (quote) {
            if (escaped) {
                escaped = false;
            } else if (ch === '\\') {
                escaped = true;
            } else if (ch === quote) {
                quote = null;
            }
            i += 1;
            continue;
        }

        if (ch === '\'' || ch === '"') {
            quote = ch;
            i += 1;
            continue;
        }

        if (ch === '(' || ch === '[' || ch === '{') {
            depth += 1;
        } else if (ch === ')' || ch === ']' || ch === '}') {
            if (depth > 0) {
                depth -= 1;
            } else if (ch === ')') {
                return { value: text.slice(startIndex, i), endIndex: i + 1 };
            }
        }

        i += 1;
    }
    return { value: text.slice(startIndex), endIndex: text.length };
};

const splitTopLevel = (text, delimiter) => {
    const parts = [];
    let current = '';
    let depth = 0;
    let quote = null;
    let escaped = false;
    for (let i = 0; i < text.length; i += 1) {
        const ch = text[i];
        if (quote) {
            current += ch;
            if (escaped) {
                escaped = false;
            } else if (ch === '\\') {
                escaped = true;
            } else if (ch === quote) {
                quote = null;
            }
            continue;
        }

        if (ch === '\'' || ch === '"') {
            quote = ch;
            current += ch;
            continue;
        }

        if (ch === '(' || ch === '[' || ch === '{') {
            depth += 1;
        } else if (ch === ')' || ch === ']' || ch === '}') {
            if (depth > 0) {
                depth -= 1;
            }
        }

        if (ch === delimiter && depth === 0) {
            parts.push(current);
            current = '';
            continue;
        }

        current += ch;
    }
    if (current) {
        parts.push(current);
    }
    return parts;
};

const parsePhpStringLiteral = (segment) => {
    const trimmed = segment.trim();
    const quote = trimmed[0];
    if (quote !== '\'' && quote !== '"') {
        return null;
    }

    let result = '';
    let escaped = false;
    for (let i = 1; i < trimmed.length; i += 1) {
        const ch = trimmed[i];
        if (escaped) {
            result += ch;
            escaped = false;
            continue;
        }
        if (ch === '\\') {
            escaped = true;
            continue;
        }
        if (ch === quote) {
            break;
        }
        result += ch;
    }

    if (quote === '"') {
        result = result.replace(/\{\$[A-Za-z_][A-Za-z0-9_]*\}/g, '${}');
        result = result.replace(/\$[A-Za-z_][A-Za-z0-9_]*\b/g, '${}');
    }

    return result;
};

const parsePhpExprToTemplate = (expr, assignments, callPos) => {
    const segments = splitTopLevel(expr, '.');
    const parts = [];
    let hasLiteral = false;

    for (const rawSegment of segments) {
        let segment = rawSegment.trim();
        while (segment.startsWith('(') && segment.endsWith(')')) {
            segment = segment.slice(1, -1).trim();
        }
        if (!segment) {
            continue;
        }

        const stringLiteral = parsePhpStringLiteral(segment);
        if (stringLiteral !== null) {
            parts.push(stringLiteral);
            hasLiteral = true;
            continue;
        }

        const varMatch = segment.match(/^\$([A-Za-z_][A-Za-z0-9_]*)$/);
        if (varMatch) {
            const varName = varMatch[1];
            const assignment = resolveAssignment(assignments, varName, callPos);
            if (assignment) {
                parts.push(assignment.template);
                hasLiteral = hasLiteral || assignment.hasLiteral;
            } else {
                parts.push('${}');
            }
            continue;
        }

        parts.push('${}');
    }

    return {
        template: parts.join(''),
        hasLiteral,
    };
};

const resolveAssignment = (assignments, name, callPos) => {
    const list = assignments.get(name);
    if (!list) {
        return null;
    }
    let candidate = null;
    for (const entry of list) {
        if (entry.pos <= callPos) {
            if (!candidate || entry.pos > candidate.pos) {
                candidate = entry;
            }
        }
    }
    return candidate;
};

const findPhpAssignments = (text) => {
    const assignments = new Map();
    const regex = /\$([A-Za-z_][A-Za-z0-9_]*)\s*=/g;
    while (true) {
        const match = regex.exec(text);
        if (!match) {
            break;
        }
        const varName = match[1];
        const afterIndex = match.index + match[0].length;
        const afterChar = text[afterIndex] ?? '';
        if (afterChar === '=' || afterChar === '>') {
            continue;
        }
        const { value, endIndex } = scanToDelimiter(text, afterIndex, ';');
        const parsed = parsePhpExprToTemplate(value, new Map(), match.index);
        if (!parsed.hasLiteral) {
            regex.lastIndex = endIndex;
            continue;
        }
        const list = assignments.get(varName) ?? [];
        list.push({
            pos: match.index,
            template: parsed.template,
            hasLiteral: parsed.hasLiteral,
        });
        assignments.set(varName, list);
        regex.lastIndex = endIndex;
    }
    return assignments;
};

const PHP_LOG_METHODS = {
    pushGeneralActionLog: { scope: 'GENERAL', category: 'ACTION', generalMethod: true },
    pushGeneralHistoryLog: { scope: 'GENERAL', category: 'HISTORY', generalMethod: true },
    pushNationalActionLog: { scope: 'NATION', category: 'ACTION', generalMethod: false },
    pushNationalHistoryLog: { scope: 'NATION', category: 'HISTORY', generalMethod: false },
    pushGlobalActionLog: { scope: 'SYSTEM', category: 'ACTION', generalMethod: false },
    pushGlobalHistoryLog: { scope: 'SYSTEM', category: 'HISTORY', generalMethod: false },
};

const findPhpActorGeneralVars = (text) => {
    const vars = new Set();
    const regex = /\$([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\$this->generalObj\s*;/g;
    while (true) {
        const match = regex.exec(text);
        if (!match) {
            break;
        }
        vars.add(match[1]);
    }
    return vars;
};

const findPhpActorLoggerVars = (text, actorGeneralVars) => {
    const vars = new Set(['logger']);
    const regex =
        /\$([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\$this->generalObj|\$[A-Za-z_][A-Za-z0-9_]*)\s*->\s*getLogger\s*\(\s*\)\s*;/g;
    while (true) {
        const match = regex.exec(text);
        if (!match) {
            break;
        }
        const lhs = match[1];
        const rhs = match[2];
        if (rhs === '$this->generalObj') {
            vars.add(lhs);
            continue;
        }
        const generalVar = rhs.slice(1);
        if (actorGeneralVars.has(generalVar)) {
            vars.add(lhs);
        }
    }
    return vars;
};

const isPhpActorLoggerExpr = (calleeExpr, actorGeneralVars, actorLoggerVars) => {
    if (!calleeExpr) {
        return false;
    }

    if (calleeExpr.includes('$this->generalObj->getLogger(')) {
        return true;
    }

    for (const actorGeneralVar of actorGeneralVars) {
        if (calleeExpr.includes(`$${actorGeneralVar}->getLogger(`)) {
            return true;
        }
    }

    const variableOnly = calleeExpr.match(/^\$([A-Za-z_][A-Za-z0-9_]*)$/);
    if (variableOnly) {
        return actorLoggerVars.has(variableOnly[1]);
    }

    return false;
};

const extractPhpLogCalls = (text, assignments) => {
    const results = [];
    const lineStarts = buildLineIndex(text);
    const actorGeneralVars = findPhpActorGeneralVars(text);
    const actorLoggerVars = findPhpActorLoggerVars(text, actorGeneralVars);
    const regex =
        /([$\w><:\-\(\)]+)\s*->\s*(pushGeneralActionLog|pushGeneralHistoryLog|pushNationalActionLog|pushNationalHistoryLog|pushGlobalActionLog|pushGlobalHistoryLog)\s*\(/g;

    while (true) {
        const match = regex.exec(text);
        if (!match) {
            break;
        }

        const calleeExpr = match[1]?.trim() ?? '';
        const methodName = match[2];
        const methodMeta = PHP_LOG_METHODS[methodName];
        if (!methodMeta) {
            continue;
        }

        const startIndex = match.index + match[0].length;
        const { value, endIndex, endedBy } = scanToFirstArgumentEnd(text, startIndex);
        let format = null;
        if (endedBy === ',') {
            const rest = text.slice(endIndex);
            const afterComma = endIndex + (rest.match(/^\s*/)?.[0].length ?? 0);
            const { value: secondArg } = scanToParenEnd(text, afterComma);
            const formatMatch = secondArg.match(/ActionLogger::([A-Za-z_]+)/);
            if (formatMatch) {
                format = formatMatch[1];
            }
        }

        const parsed = parsePhpExprToTemplate(value, assignments, match.index);
        const hasGeneralId = methodMeta.generalMethod && !isPhpActorLoggerExpr(calleeExpr, actorGeneralVars, actorLoggerVars);

        results.push({
            pos: match.index,
            raw: value.trim(),
            template: parsed.template,
            line: getLineNumber(lineStarts, match.index),
            format,
            category: methodMeta.category,
            scope: methodMeta.scope,
            hasGeneralId,
        });
    }

    return results;
};

// ===========================================================================
// NEW: Kotlin source extractor (replaces the legacy TS `ts`-parser extractor).
//
// Dependency-free regex/brace scan over the :logic resolver tree. Mirrors the PHP
// extractor's shape so the SAME normalizer/mode-filter/report apply unchanged:
//
//   * The acting-general action-log scope is `context.addLog(...)` / `addPlainLog(...)`
//     (PHP pushGeneralActionLog on the actor's own logger → GENERAL/ACTION,
//     hasGeneralId=false → kept in action mode).
//   * `context.addGlobalActionLog(...)` is the broadcast scope (PHP pushGlobalActionLog →
//     SYSTEM/ACTION → dropped by the default action-mode scope filter, exactly as the PHP
//     SYSTEM logs are).
//   * `context.addLogTo(id, ...)` / `addPlainLogTo(id, ...)` is the dest-general scope
//     (PHP pushGeneralActionLog on a SECOND general's logger → hasGeneralId=true → dropped
//     as a target log by default, exactly as PHP target logs are).
//
// Kotlin string templates ($var / ${expr}) survive to the SHARED normalizer, which already
// collapses both `$var` and `${...}` to `${}`. Local string vars assigned via
// `val x = "..."` or `val x = when(...) { -> "..." ... }` are resolved so `addLog(log)`
// (CommerceInvestment) and `addLog(builtLine)` carry their literal template, mirroring the
// PHP `findPhpAssignments` pass. Log calls in a shared base class (CommerceInvestment,
// JoinCommand) are attributed to every concrete subclass key.
// ===========================================================================

const KOTLIN_LOG_METHODS = {
    addLog: { scope: 'GENERAL', category: 'ACTION', target: false },
    addPlainLog: { scope: 'GENERAL', category: 'ACTION', target: false },
    addGlobalActionLog: { scope: 'SYSTEM', category: 'ACTION', target: false },
    addLogTo: { scope: 'GENERAL', category: 'ACTION', target: true },
    addPlainLogTo: { scope: 'GENERAL', category: 'ACTION', target: true },
};

// A Kotlin double-quoted string literal (single-line OR triple-quoted), returning the raw
// contents with templates left intact for the shared normalizer.
const parseKotlinStringAt = (text, openIndex) => {
    // triple-quoted
    if (text.startsWith('"""', openIndex)) {
        const end = text.indexOf('"""', openIndex + 3);
        if (end < 0) return null;
        return { value: text.slice(openIndex + 3, end), endIndex: end + 3 };
    }
    if (text[openIndex] !== '"') return null;
    let i = openIndex + 1;
    let result = '';
    let escaped = false;
    while (i < text.length) {
        const ch = text[i];
        if (escaped) {
            // keep the un-escaped char (templates like \" / \n are rare in these bodies)
            result += ch === 'n' ? '\n' : ch === 't' ? '\t' : ch;
            escaped = false;
            i += 1;
            continue;
        }
        if (ch === '\\') {
            escaped = true;
            i += 1;
            continue;
        }
        if (ch === '"') {
            return { value: result, endIndex: i + 1 };
        }
        if (ch === '\n') {
            // an un-terminated single-line literal — bail
            return null;
        }
        result += ch;
        i += 1;
    }
    return null;
};

// Resolve `val|var NAME = "..."` and `val|var NAME = when (...) { ... -> "..."; ... }`
// to one-or-many template strings. when-arms contribute each a template (a command emits
// exactly one of the branches at runtime, so for the template SURFACE all arms are valid
// templates — the captured golden carries whichever arm fired). Mirrors the PHP
// assignment resolver but Kotlin-flavoured.
// Slice the RHS of a `when`/`if-else` value assignment starting at `start` (the `when`/`if`
// keyword). Returns the substring spanning the whole conditional value so its top-level string
// literals can be harvested. Handles: a brace-balanced `when { ... }`; an `if (cond) <then> else
// <else>` chain where each branch is either a `{ ... }` block or a single expression terminated
// by newline / `else` / a top-level `}`. String/paren/brace nesting is respected.
const sliceAssignmentRhs = (text, start) => {
    let i = start;
    const isWhen = text.startsWith('when', i);
    // advance past an optional `(...)` subject/condition
    const skipParens = (k) => {
        while (k < text.length && /\s/.test(text[k])) k += 1;
        if (text[k] !== '(') return k;
        let depth = 0, quote = null, esc = false;
        for (; k < text.length; k += 1) {
            const ch = text[k];
            if (quote) { if (esc) esc = false; else if (ch === '\\') esc = true; else if (ch === quote) quote = null; continue; }
            if (ch === '"') { quote = '"'; continue; }
            if (ch === '(') depth += 1;
            else if (ch === ')') { depth -= 1; if (depth === 0) return k + 1; }
        }
        return k;
    };
    // a balanced `{ ... }` block ending position (exclusive); assumes text[k] === '{'
    const skipBlock = (k) => {
        let depth = 0, quote = null, esc = false;
        for (; k < text.length; k += 1) {
            const ch = text[k];
            if (quote) { if (esc) esc = false; else if (ch === '\\') esc = true; else if (ch === quote) quote = null; continue; }
            if (ch === '"') { quote = '"'; continue; }
            if (ch === '{') depth += 1;
            else if (ch === '}') { depth -= 1; if (depth === 0) return k + 1; }
        }
        return k;
    };
    // a single-expression branch: a string literal or up to newline / `else` / top-level `}`
    const skipExpr = (k) => {
        while (k < text.length && /[ \t]/.test(text[k])) k += 1;
        if (text[k] === '{') return skipBlock(k);
        if (text[k] === '"') { const lit = parseKotlinStringAt(text, k); return lit ? lit.endIndex : k + 1; }
        let depth = 0, quote = null, esc = false;
        for (; k < text.length; k += 1) {
            const ch = text[k];
            if (quote) { if (esc) esc = false; else if (ch === '\\') esc = true; else if (ch === quote) quote = null; continue; }
            if (ch === '"') { quote = '"'; continue; }
            if (ch === '(' || ch === '{' || ch === '[') depth += 1;
            else if (ch === ')' || ch === '}' || ch === ']') { if (depth === 0) return k; depth -= 1; }
            else if (ch === '\n' && depth === 0) return k;
        }
        return k;
    };

    if (isWhen) {
        i += 'when'.length;
        i = skipParens(i);
        while (i < text.length && /\s/.test(text[i])) i += 1;
        if (text[i] === '{') return text.slice(start, skipBlock(i));
        return text.slice(start, i);
    }
    // if-else chain
    i += 'if'.length;
    while (true) {
        i = skipParens(i);                 // condition
        i = skipExpr(i);                   // then-branch
        // optional `else` (possibly `else if`)
        let k = i;
        while (k < text.length && /\s/.test(text[k])) k += 1;
        if (text.startsWith('else', k)) {
            k += 'else'.length;
            while (k < text.length && /\s/.test(text[k])) k += 1;
            if (text.startsWith('if', k)) { i = k + 'if'.length; continue; }
            i = skipExpr(k);
        }
        break;
    }
    return text.slice(start, i);
};

const findKotlinStringVars = (text) => {
    const vars = new Map(); // name -> [{pos, templates: string[]}]
    const regex = /\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*/g;
    while (true) {
        const m = regex.exec(text);
        if (!m) break;
        const name = m[1];
        let i = m.index + m[0].length;
        // skip leading whitespace
        while (i < text.length && /\s/.test(text[i])) i += 1;
        const templates = [];
        if (text[i] === '"') {
            // simple `val x = "..."`
            const lit = parseKotlinStringAt(text, i);
            if (lit) templates.push(lit.value);
        } else if (text.startsWith('when', i)) {
            // `val x = when (subject) { "fail" -> "...A"; "ok" -> "...B"; else -> "...C" }`.
            // The value is exactly ONE arm BODY at runtime; for the template SURFACE every arm body
            // is a valid template (the captured golden carries whichever fired). Harvest ONLY the
            // arm-body literal that follows each `->` — NOT the `"fail"`/`"ok"` case-LABEL literals
            // on the left of `->`.
            const region = sliceAssignmentRhs(text, i);
            const arm = /->\s*/g;
            let am;
            while ((am = arm.exec(region))) {
                let k = am.index + am[0].length;
                if (region[k] === '"') {
                    const lit = parseKotlinStringAt(region, k);
                    if (lit) templates.push(lit.value);
                }
            }
        } else if (text.startsWith('if', i)) {
            // `val x = if (...) "...A" else "...B"` (no case labels) — harvest all top-level
            // literals across the branches. Mirrors the PHP assignment resolver, Kotlin-flavoured.
            const region = sliceAssignmentRhs(text, i);
            let q = 0;
            while (q < region.length) {
                if (region[q] === '"') {
                    const lit = parseKotlinStringAt(region, q);
                    if (lit) { templates.push(lit.value); q = lit.endIndex; continue; }
                }
                q += 1;
            }
        }
        if (templates.length > 0) {
            const list = vars.get(name) ?? [];
            list.push({ pos: m.index, templates });
            vars.set(name, list);
        }
    }
    return vars;
};

const resolveKotlinVar = (vars, name, callPos) => {
    const list = vars.get(name);
    if (!list) return null;
    let best = null;
    for (const e of list) {
        if (e.pos <= callPos && (!best || e.pos > best.pos)) best = e;
    }
    return best ? best.templates : null;
};

// Extract the log calls from one Kotlin file. Returns entries identical in shape to the PHP
// extractor (template/scope/category/hasGeneralId) so the shared filter/report apply.
const extractKotlinLogCalls = (text) => {
    const results = [];
    const lineStarts = buildLineIndex(text);
    const vars = findKotlinStringVars(text);
    const regex = /context\s*\.\s*(addLog|addPlainLog|addGlobalActionLog|addLogTo|addPlainLogTo)\s*\(/g;
    while (true) {
        const m = regex.exec(text);
        if (!m) break;
        const method = m[1];
        const meta = KOTLIN_LOG_METHODS[method];
        if (!meta) continue;
        const startIndex = m.index + m[0].length;

        // skip to the first NON-id-arg for the *To methods (the first arg is the target id).
        let argStart = startIndex;
        if (meta.target) {
            const { endIndex, endedBy } = scanToFirstArgumentEnd(text, startIndex);
            if (endedBy === ',') argStart = endIndex;
        }
        // skip whitespace + newlines to the template/identifier
        let i = argStart;
        while (i < text.length && /\s/.test(text[i])) i += 1;

        const line = getLineNumber(lineStarts, m.index);
        const pushTemplate = (tpl) => {
            results.push({
                pos: m.index,
                raw: tpl,
                template: tpl,
                line,
                format: null,
                category: meta.category,
                scope: meta.scope,
                hasGeneralId: meta.target,
            });
        };

        if (text[i] === '"') {
            const lit = parseKotlinStringAt(text, i);
            if (lit) {
                pushTemplate(lit.value);
                regex.lastIndex = lit.endIndex;
                continue;
            }
        }
        // bare identifier → resolve a local string/when var (addLog(log), addLog(line), …)
        const idMatch = /^([A-Za-z_][A-Za-z0-9_]*)\s*[,)]/.exec(text.slice(i));
        if (idMatch) {
            const resolved = resolveKotlinVar(vars, idMatch[1], m.index);
            if (resolved) {
                for (const tpl of resolved) pushTemplate(tpl);
                continue;
            }
        }
        // `it` / member-access / forwarded-helper line we cannot resolve statically:
        // emit a pure-template placeholder (PHP side does the same when it can't resolve).
        pushTemplate('${}');
    }
    return results;
};

// Map every Kotlin resolver file to the command key(s) it implements + the log calls that
// belong to those keys, threading shared base classes (CommerceInvestment, JoinCommand).
const findKotlinKeys = (text) => {
    const keys = [];
    const regex = /override\s+val\s+key\s*(?::\s*String\s*)?(?:get\(\)\s*)?=\s*"([^"]+)"/g;
    let m;
    while ((m = regex.exec(text))) keys.push(m[1]);
    return keys;
};

const findKotlinSuperclass = (text) => {
    // `class Foo(... ) : SuperType(... ), Iface {`  — the first type after the primary `:`.
    const m = /class\s+[A-Za-z0-9_]+\s*(?:\([^)]*\)|<[^>]*>)?\s*:\s*([A-Za-z0-9_]+)/.exec(text);
    return m ? m[1] : null;
};

// ===========================================================================
// KEPT VERBATIM: normalizeTemplate + guard/target/mode filters + ignore rules +
// report builder + checklist.
// ===========================================================================

const normalizeTemplate = (text) => {
    if (strict) {
        return text.trim();
    }
    let out = text;
    if (!keepDate) {
        out = out.replace(/<1>.*?<\/>/g, '');
    }
    out = out.replace(/<\/?b>/g, '');
    out = out.replace(/\{\$\{[^}]*\}[^}]*\}/g, '${}');
    out = out.replace(/\$\{[^}]*\}/g, '${}');
    out = out.replace(/\{\$[A-Za-z_][A-Za-z0-9_]*\}/g, '${}');
    out = out.replace(/\$[A-Za-z_][A-Za-z0-9_]*\b/g, '${}');
    out = out.replace(/\s+/g, ' ').trim();
    return out;
};

const isGuardLog = (template) => guardPatterns.some((pattern) => pattern.test(normalizeTemplate(template)));

const isTargetLog = (entry) => {
    if (!entry) {
        return false;
    }
    return !!entry.hasGeneralId;
};

const loadIgnoreConfig = async () => {
    try {
        const raw = await fs.readFile(path.join(ROOT_DIR, ignoreFile), 'utf-8');
        const parsed = JSON.parse(raw);
        return parsed ?? {};
    } catch (error) {
        if (error && error.code === 'ENOENT') {
            return {};
        }
        throw error;
    }
};

const compileIgnoreRules = (config) => {
    const global = config.Global ?? {};
    const normalizeList = (list) => (Array.isArray(list) ? list.map((item) => normalizeTemplate(String(item))) : []);
    const compileRegex = (list) =>
        Array.isArray(list) ? list.map((item) => new RegExp(String(item))) : [];

    const reserved = new Set(['Global', 'IgnoreCommands', '_doc']);
    return {
        // Whole-command ignore: every key in IgnoreCommands is dropped from the matched set
        // (the 12 backlogged commands + the ~57-of-93 non-P2 commands).
        ignoreCommands: new Set(Array.isArray(config.IgnoreCommands) ? config.IgnoreCommands : []),
        globalTemplates: new Set(normalizeList(global.templates)),
        globalRegex: compileRegex(global.regex),
        perCommand: new Map(
            Object.entries(config)
                .filter(([key]) => !reserved.has(key))
                .map(([key, value]) => [
                    key,
                    {
                        templates: new Set(normalizeList(value?.templates)),
                        regex: compileRegex(value?.regex),
                    },
                ])
        ),
    };
};

const shouldIgnoreTemplate = (key, template, rules) => {
    const normalized = normalizeTemplate(template);
    if (rules.globalTemplates.has(normalized)) {
        return true;
    }
    if (rules.globalRegex.some((regex) => regex.test(normalized))) {
        return true;
    }
    const commandRule = rules.perCommand.get(key);
    if (!commandRule) {
        return false;
    }
    if (commandRule.templates.has(normalized)) {
        return true;
    }
    if (commandRule.regex.some((regex) => regex.test(normalized))) {
        return true;
    }
    return false;
};

const shouldIncludeEntryByMode = (entry) => {
    if (mode === 'all') {
        return true;
    }
    const category = entry.category;
    const scope = entry.scope;
    if (mode === 'action') {
        if (category && category !== 'ACTION') {
            return false;
        }
        if (scope && scope !== 'GENERAL') {
            return false;
        }
        return true;
    }
    if (mode === 'history') {
        if (category && category !== 'HISTORY') {
            return false;
        }
        if (scope && scope !== 'GENERAL') {
            return false;
        }
        return true;
    }
    return true;
};

const filterCommandKey = (key) => {
    if (!includeRegex) {
        return true;
    }
    return includeRegex.test(key);
};

const formatEntries = (entries) => {
    const map = new Map();
    for (const entry of entries) {
        const key = normalizeTemplate(entry.template);
        const list = map.get(key) ?? [];
        list.push(entry);
        map.set(key, list);
    }
    const lines = [];
    for (const [template, list] of map.entries()) {
        const first = list[0];
        const extra = list.length > 1 ? ` (+${list.length - 1} more)` : '';
        lines.push(`${first.file}:${first.line}${extra} | ${normalizeTemplate(template)}`);
    }
    return lines;
};

const buildTemplateCountMap = (entries) => {
    const map = new Map();
    for (const entry of entries) {
        const template = normalizeTemplate(entry.template);
        map.set(template, (map.get(template) ?? 0) + 1);
    }
    return map;
};

const diffTemplateCounts = (lhsCounts, rhsCounts) => {
    const items = [];
    for (const [template, lhsCount] of lhsCounts.entries()) {
        const rhsCount = rhsCounts.get(template) ?? 0;
        if (lhsCount > rhsCount) {
            items.push({ template, count: lhsCount - rhsCount });
        }
    }
    return items;
};

// Bare command short name (strip the General/Nation dir prefix on the PHP side).
const bareKey = (key) => {
    const slash = key.lastIndexOf('/');
    return slash >= 0 ? key.slice(slash + 1) : key;
};

const loadPhpLogs = async () => {
    const files = (await collectFiles(PHP_ROOT)).filter((file) => file.endsWith('.php'));

    // Pass 1: per-class raw logs + parent class (PHP develop/military commands inherit run():
    // `class che_농지개간 extends che_상업투자` / `class che_모병 extends che_징병` carry NO own
    // push*Log — the template lives on the parent. Thread the `extends` chain so the inherited
    // template is attributed, mirroring the Kotlin base-class threading.)
    const byClass = new Map(); // className -> { rawLogs, parent, fileRel }
    for (const file of files) {
        const baseName = path.basename(file, '.php');
        if (['BaseCommand', 'GeneralCommand', 'NationCommand'].includes(baseName)) {
            continue;
        }
        const text = await fs.readFile(file, 'utf-8');
        const parentMatch = /class\s+[^\s]+\s+extends\s+([A-Za-z_\x80-￿][\w\x80-￿]*)/.exec(text);
        const assignments = findPhpAssignments(text);
        const rawLogs = extractPhpLogCalls(text, assignments).map((log) => ({
            file: path.relative(ROOT_DIR, file),
            line: log.line,
            template: log.template,
            raw: log.raw,
            category: log.category,
            scope: log.scope,
            format: log.format,
            hasGeneralId: log.hasGeneralId,
        }));
        byClass.set(baseName, { rawLogs, parent: parentMatch ? parentMatch[1] : null });
    }

    const collectInherited = (className, seen) => {
        const info = byClass.get(className);
        if (!info || seen.has(className)) return [];
        seen.add(className);
        const out = [...info.rawLogs];
        if (info.parent) out.push(...collectInherited(info.parent, seen));
        return out;
    };

    const logsByKey = new Map();
    for (const [className] of byClass) {
        const key = bareKey(className);
        if (!filterCommandKey(key)) continue;
        const logs = collectInherited(className, new Set());
        if (logs.length === 0) continue;
        const filtered = logs.filter((entry) => {
            if (!shouldIncludeEntryByMode(entry)) return false;
            if (excludeGuards && isGuardLog(entry.template)) return false;
            if (excludeTarget && isTargetLog(entry)) return false;
            return true;
        });
        if (filtered.length === 0) continue;
        logsByKey.set(key, filtered);
    }

    return logsByKey;
};

// Parse CommandRegistry.kt `resolve(actionCode)` `when` arms: `"<key>" -> <expr>`. The expr's
// root identifier is the resolver factory/constructor (cheSangeobTuja / CheJangbiMaemae /
// RecruitAlgorithm.cheJingbyeong). This is the AUTHORITATIVE action-code → resolver binding —
// it ties computed-key resolvers (CommerceInvestment via che_${name}, RecruitAlgorithm via
// che_$name) to their concrete captured keys, which a raw `override val key` scan cannot.
const parseRegistryArms = (text) => {
    const arms = [];
    const regex = /"([^"]+)"\s*->\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\(/g;
    let m;
    while ((m = regex.exec(text))) {
        const key = m[1];
        // root identifier = the segment after the last `.` (RecruitAlgorithm.cheJingbyeong → cheJingbyeong)
        const callee = m[2];
        const root = callee.includes('.') ? callee.slice(callee.lastIndexOf('.') + 1) : callee;
        arms.push({ key, callee, root });
    }
    return arms;
};

// Per-file source intel: defined symbols (classes / funcs / object factories), the type each
// class/factory delegates to (superclass or the `= SomeType(...)` / `object : SomeType()` it
// builds), and the file's log calls.
const buildSymbolIndex = (fileInfo) => {
    const defines = new Map(); // symbol name -> file rel path
    for (const info of fileInfo) {
        for (const sym of info.symbols) defines.set(sym, info.rel);
    }
    return defines;
};

const loadKotlinLogs = async () => {
    const files = (await collectFiles(KOTLIN_ROOT)).filter((file) => file.endsWith('.kt'));
    const fileInfo = [];
    let registryArms = [];
    for (const file of files) {
        const text = await fs.readFile(file, 'utf-8');
        const rel = path.relative(ROOT_DIR, file);
        // Every top-level / companion symbol this file defines, so a registry arm's root id
        // (factory fun OR class ctor) resolves to its file.
        const symbols = new Set();
        let sm;
        const symRegex = /\b(?:fun|(?:abstract\s+|open\s+|sealed\s+)*class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)/g;
        while ((sm = symRegex.exec(text))) symbols.add(sm[1]);
        // The delegate types this file's classes/factories reach into (superclass `: Type(` and
        // built `= Type(` / `object : Type(`), so base-class / delegated log calls are picked up.
        const delegates = new Set();
        let dm;
        const delRegex = /(?::\s*|=\s*|object\s*:\s*)([A-Z][A-Za-z0-9_]*)\s*\(/g;
        while ((dm = delRegex.exec(text))) delegates.add(dm[1]);
        const info = {
            file, rel,
            symbols: [...symbols],
            delegates: [...delegates],
            typeName: (/(?:abstract\s+)?(?:open\s+)?class\s+([A-Za-z0-9_]+)/.exec(text) || [])[1] || null,
            logs: extractKotlinLogCalls(text),
        };
        fileInfo.push(info);
        if (path.basename(file) === 'CommandRegistry.kt') registryArms = parseRegistryArms(text);
    }

    const definesSymbol = buildSymbolIndex(fileInfo);
    const byRel = new Map(fileInfo.map((info) => [info.rel, info]));

    // Aggregate a file's log calls + its delegate types' log calls (transitively), so a resolver
    // that delegates to a base class / shared command base picks up the inherited templates.
    const collectLogsForFile = (rel, seenRels) => {
        const info = byRel.get(rel);
        if (!info || seenRels.has(rel)) return [];
        seenRels.add(rel);
        const out = [...info.logs];
        for (const del of info.delegates) {
            const delRel = definesSymbol.get(del);
            if (delRel && delRel !== rel) out.push(...collectLogsForFile(delRel, seenRels));
        }
        return out;
    };

    const logsByKey = new Map();

    // Build (key -> resolver file) bindings. Prefer the registry arms; for --all-commands, also
    // fold in any file's own declared keys not in the registry.
    const bindings = [];
    for (const arm of registryArms) {
        const rel = definesSymbol.get(arm.root);
        if (rel) bindings.push({ key: arm.key, rel });
    }
    if (allCommands) {
        for (const info of fileInfo) {
            const text = await fs.readFile(info.file, 'utf-8');
            for (const key of findKotlinKeys(text)) {
                if (!bindings.some((b) => b.key === key)) bindings.push({ key, rel: info.rel });
            }
        }
    }

    for (const { key, rel } of bindings) {
        if (!filterCommandKey(key)) continue;
        const collected = collectLogsForFile(rel, new Set());
        const entries = collected
            .filter((entry) => shouldIncludeEntryByMode(entry))
            .map((entry) => ({
                file: rel,
                line: entry.line,
                template: entry.template,
                raw: entry.raw,
                category: entry.category,
                scope: entry.scope,
                format: entry.format,
                hasGeneralId: entry.hasGeneralId,
            }))
            .filter((entry) => {
                if (excludeGuards && isGuardLog(entry.template)) return false;
                if (excludeTarget && isTargetLog(entry)) return false;
                // drop the un-resolvable pure-placeholder (forwarded checkStatChange / lottery
                // PLAIN lines: `addPlainLog(it)` over level-change/lottery lines — these are
                // dynamic, never a fixed command template, and the PHP side likewise has no
                // fixed template for them).
                if (normalizeTemplate(entry.template) === '${}') return false;
                return true;
            });
        if (entries.length === 0) continue;
        const list = logsByKey.get(key) ?? [];
        list.push(...entries);
        logsByKey.set(key, list);
    }
    return logsByKey;
};

// The matched set = the committed PHP-captured goldens (bare command keys).
const loadCapturedKeys = async () => {
    try {
        const files = await fs.readdir(GOLDEN_DIR);
        return new Set(
            files
                .filter((f) => f.endsWith('-fixtures.json'))
                .map((f) => f.replace(/-fixtures\.json$/, ''))
        );
    } catch (error) {
        if (error && error.code === 'ENOENT') return new Set();
        throw error;
    }
};

const buildReport = (phpLogs, kotlinLogs, ignoreRules, capturedKeys) => {
    const allKeys = new Set([...phpLogs.keys(), ...kotlinLogs.keys()]);
    // Scope to the captured set unless --all-commands. Drop whole-command-ignored keys.
    const scopedKeys = [...allKeys].filter((key) => {
        if (rulesIgnoreWholeCommand(ignoreRules, key)) return false;
        if (allCommands) return true;
        return capturedKeys.has(key);
    });
    const sortedKeys = scopedKeys.sort();

    const missingInKotlin = [];
    const missingInPhp = [];
    const mismatches = [];
    const matches = [];
    const ignored = [];

    for (const key of sortedKeys) {
        const phpEntries = phpLogs.get(key) ?? [];
        const kotlinEntries = kotlinLogs.get(key) ?? [];
        if (phpEntries.length === 0) {
            missingInPhp.push(key);
            continue;
        }
        if (kotlinEntries.length === 0) {
            missingInKotlin.push(key);
            continue;
        }

        const rawMissingDetails = [];
        const rawExtraDetails = [];
        if (countSensitive) {
            const phpCounts = buildTemplateCountMap(phpEntries);
            const kotlinCounts = buildTemplateCountMap(kotlinEntries);
            rawMissingDetails.push(...diffTemplateCounts(phpCounts, kotlinCounts));
            rawExtraDetails.push(...diffTemplateCounts(kotlinCounts, phpCounts));
        } else {
            const phpSet = new Set(phpEntries.map((entry) => normalizeTemplate(entry.template)));
            const kotlinSet = new Set(kotlinEntries.map((entry) => normalizeTemplate(entry.template)));
            for (const template of phpSet) {
                if (!kotlinSet.has(template)) {
                    rawMissingDetails.push({ template, count: 1 });
                }
            }
            for (const template of kotlinSet) {
                if (!phpSet.has(template)) {
                    rawExtraDetails.push({ template, count: 1 });
                }
            }
        }

        const missingDetails = rawMissingDetails.filter(
            (item) => !shouldIgnoreTemplate(key, item.template, ignoreRules)
        );
        const extraDetails = rawExtraDetails.filter((item) => !shouldIgnoreTemplate(key, item.template, ignoreRules));
        const ignoredMissingDetails = rawMissingDetails.filter(
            (item) => !missingDetails.some((kept) => kept.template === item.template)
        );
        const ignoredExtraDetails = rawExtraDetails.filter(
            (item) => !extraDetails.some((kept) => kept.template === item.template)
        );

        const missing = missingDetails.map((item) => item.template);
        const extra = extraDetails.map((item) => item.template);
        const ignoredMissing = ignoredMissingDetails.map((item) => item.template);
        const ignoredExtra = ignoredExtraDetails.map((item) => item.template);

        if (missing.length === 0 && extra.length === 0) {
            matches.push(key);
        } else {
            mismatches.push({ key, phpEntries, kotlinEntries, missing, extra, missingDetails, extraDetails });
        }
        if (ignoredMissing.length > 0 || ignoredExtra.length > 0) {
            ignored.push({
                key,
                missing: ignoredMissing,
                extra: ignoredExtra,
                missingDetails: ignoredMissingDetails,
                extraDetails: ignoredExtraDetails,
            });
        }
    }

    return {
        totals: {
            phpCommands: phpLogs.size,
            kotlinCommands: kotlinLogs.size,
            capturedSet: capturedKeys.size,
            comparedCommands: sortedKeys.length,
            matches: matches.length,
            mismatches: mismatches.length,
            ignored: ignored.length,
        },
        matchedKeys: matches.sort(),
        missingInKotlin,
        missingInPhp,
        mismatches,
        ignored,
    };
};

const rulesIgnoreWholeCommand = (rules, key) =>
    rules.ignoreCommands.has(key) || rules.ignoreCommands.has(bareKey(key));

const renderChecklist = (report) => {
    const lines = [];
    lines.push(`# Command Log Checklist`);
    lines.push('');
    lines.push(`Mode: ${mode}`);
    lines.push(`Strict: ${strict ? 'on' : 'off'}`);
    lines.push(`Keep date: ${keepDate ? 'on' : 'off'}`);
    lines.push(`Exclude guards: ${excludeGuards ? 'on' : 'off'}`);
    lines.push(`Exclude target: ${excludeTarget ? 'on' : 'off'}`);
    lines.push(`Ignore file: ${ignoreFile}`);
    lines.push('');

    if (report.mismatches.length === 0) {
        lines.push('- [x] All command logs match.');
        return lines.join('\n');
    }

    for (const mismatch of report.mismatches) {
        lines.push(`- [ ] ${mismatch.key}`);
        if (mismatch.missing.length > 0) {
            lines.push(`PHP only: ${mismatch.missing.join(' | ')}`);
        }
        if (mismatch.extra.length > 0) {
            lines.push(`Kotlin only: ${mismatch.extra.join(' | ')}`);
        }
    }
    return lines.join('\n');
};

const main = async () => {
    const phpLogs = await loadPhpLogs();
    const kotlinLogs = await loadKotlinLogs();
    const ignoreConfig = await loadIgnoreConfig();
    const ignoreRules = compileIgnoreRules(ignoreConfig);
    const capturedKeys = await loadCapturedKeys();
    const report = buildReport(phpLogs, kotlinLogs, ignoreRules, capturedKeys);

    if (asJson) {
        console.log(JSON.stringify(report, null, 2));
    } else {
        console.log(
            `Compare command logs PHP↔Kotlin (mode: ${mode}, strict: ${strict ? 'on' : 'off'}, keepDate: ${keepDate ? 'on' : 'off'}, excludeGuards: ${excludeGuards ? 'on' : 'off'}, excludeTarget: ${excludeTarget ? 'on' : 'off'}, countSensitive: ${countSensitive ? 'on' : 'off'}, scope: ${allCommands ? 'all-commands' : 'captured-set'})`
        );
        console.log(`PHP_ROOT:    ${path.relative(ROOT_DIR, PHP_ROOT)}`);
        console.log(`KOTLIN_ROOT: ${path.relative(ROOT_DIR, KOTLIN_ROOT)}`);
        console.log(`PHP commands (with action logs): ${report.totals.phpCommands}`);
        console.log(`Kotlin commands (with action logs): ${report.totals.kotlinCommands}`);
        console.log(`Captured-golden set: ${report.totals.capturedSet}`);
        console.log(`Compared commands: ${report.totals.comparedCommands}`);
        console.log(`Matched commands: ${report.totals.matches}`);
        console.log(`Mismatched commands: ${report.totals.mismatches}`);
        console.log(`Missing in Kotlin: ${report.missingInKotlin.length}`);
        console.log(`Missing in PHP: ${report.missingInPhp.length}`);
        console.log(`Ignored mismatches: ${report.totals.ignored}`);

        if (report.matchedKeys.length > 0) {
            console.log('\nMatched commands:');
            for (const key of report.matchedKeys) console.log(`- ${key}`);
        }

        if (report.missingInKotlin.length > 0) {
            console.log('\nMissing in Kotlin:');
            for (const key of report.missingInKotlin) console.log(`- ${key}`);
        }

        if (report.missingInPhp.length > 0) {
            console.log('\nMissing in PHP:');
            for (const key of report.missingInPhp) console.log(`- ${key}`);
        }

        if (report.mismatches.length > 0) {
            console.log('\nMismatch Details:');
            for (const mismatch of report.mismatches) {
                console.log(`\n== ${mismatch.key} ==`);
                if (mismatch.missingDetails.length > 0) {
                    console.log(
                        `PHP only: ${mismatch.missingDetails
                            .map((item) => (item.count > 1 ? `${item.template} x${item.count}` : item.template))
                            .join(' | ')}`
                    );
                }
                if (mismatch.extraDetails.length > 0) {
                    console.log(
                        `Kotlin only: ${mismatch.extraDetails
                            .map((item) => (item.count > 1 ? `${item.template} x${item.count}` : item.template))
                            .join(' | ')}`
                    );
                }
                console.log('PHP:');
                for (const line of formatEntries(mismatch.phpEntries)) console.log(line);
                console.log('Kotlin:');
                for (const line of formatEntries(mismatch.kotlinEntries)) console.log(line);
            }
        }

        if (checklist) {
            console.log('\nChecklist:');
            console.log(renderChecklist(report));
        }
    }

    if (gate) {
        if (report.totals.mismatches > 0) {
            console.error(`\nGATE FAIL: ${report.totals.mismatches} mismatched command(s) over the captured set.`);
            process.exit(1);
        }
        if (report.missingInKotlin.length > 0) {
            console.error(`\nGATE FAIL: ${report.missingInKotlin.length} captured command(s) have no Kotlin resolver log.`);
            process.exit(1);
        }
        console.error(`\nGATE PASS: 0 mismatches over ${report.totals.matches} matched command(s) (captured set ${report.totals.capturedSet}).`);
    }
};

main().catch((error) => {
    console.error(error);
    process.exit(1);
});
