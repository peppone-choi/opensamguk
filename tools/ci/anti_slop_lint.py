#!/usr/bin/env python3
"""Four-rule anti-slop ratchet: fixed source debt, no automatic baseline refresh."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import time
from collections import Counter
from pathlib import Path

from lint_files import git_visible_files
from ratchet import added_paths, judge, tree_at

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path(__file__).with_name('anti_slop_lint_baseline.json')
SOURCE_COMMIT = 'fad208cdcabaf3b6b9c6f4a8e6a49824ca619a18'
SOURCE_ROOTS = ('web/game', 'web/gateway', 'web/shared')
KINDS = ('no-chained-type-assertions', 'no-widen-then-assert', 'no-reflect-apply', 'no-reflect-get')
SUFFIXES = {'.ts', '.tsx', '.mts', '.cts', '.js', '.jsx', '.mjs', '.cjs'}
SKIP = {'node_modules', '.next', '.git', '.agents', '.claude', '.codex'}
# Specific existing eslint React/Next suppressions are unrelated. Blanket or
# anti-slop suppressions are forbidden rather than becoming hidden allowances.
DIRECTIVE = re.compile(r'(?:/\*|//)\s*(?:oxlint|eslint)-disable(?:-next-line|-line)?\b([^\r\n]*)')


def finding(path: str, rule: str, source: str) -> tuple[str, str, str]:
    return path, rule, hashlib.sha256(source.encode('utf-8')).hexdigest()


def counts(findings: Counter) -> dict[str, int]:
    return {kind: sum(n for (_, rule, _), n in findings.items() if rule == kind) for kind in KINDS}


def source_files(root: Path) -> list[str]:
    visible = git_visible_files(root)
    result = []
    for directory in SOURCE_ROOTS:
        for folder, dirs, files in os.walk(root / directory):
            dirs[:] = sorted(d for d in dirs if d not in SKIP)
            for name in sorted(files):
                path = Path(folder) / name
                relative = path.relative_to(root).as_posix()
                if path.suffix not in SUFFIXES or (visible is not None and relative not in visible):
                    continue
                if path.is_symlink():
                    raise ValueError(f'symlink source is not supported: {relative}')
                result.append(relative)
    if not result:
        raise ValueError('no web source files selected')
    return sorted(result)


def parse_output(output: str, status: int, root: Path, selected: set[str],
                 sources: dict | None = None) -> Counter:
    if status not in (0, 1):
        raise ValueError(f'oxlint abnormal exit: {status}')
    try:
        doc = json.loads(output)
    except json.JSONDecodeError as error:
        raise ValueError('invalid oxlint JSON') from error
    if (not isinstance(doc, dict) or not isinstance(doc.get('diagnostics'), list)
            or doc.get('number_of_files') != len(selected) or doc.get('number_of_rules') != len(KINDS)):
        raise ValueError('missing/incomplete oxlint scan summary')
    diagnostics = doc['diagnostics']
    if status != int(bool(diagnostics)):
        raise ValueError('oxlint exit/diagnostics mismatch')
    result = Counter()
    for diagnostic in diagnostics:
        try:
            code, path = diagnostic['code'], diagnostic['filename']
            rules = {f'anti-slop({kind})': kind for kind in KINDS}
            if code not in rules or diagnostic['severity'] != 'error' or path not in selected:
                raise ValueError(f'unexpected oxlint diagnostic: {diagnostic}')
            span = diagnostic['labels'][0]['span']
            offset, length = span['offset'], span['length']
            data = (root / path).read_bytes()
            if type(offset) is not int or type(length) is not int or offset < 0 or length <= 0 or offset + length > len(data):
                raise ValueError('invalid diagnostic span')
            source = data[offset:offset + length].decode('utf-8')
            key = finding(path, rules[code], source)
            result[key] += 1
            if sources is not None:
                sources[key] = source
        except (KeyError, IndexError, TypeError, UnicodeError) as error:
            raise ValueError('malformed oxlint diagnostic') from error
    return result


def scan(root: Path, sources: dict | None = None) -> Counter:
    selected = source_files(root)
    for relative in selected:
        for match in DIRECTIVE.finditer((root / relative).read_text(encoding='utf-8')):
            rules = match.group(1).split('--', 1)[0].replace('*/', '').strip()
            if not rules or 'anti-slop' in rules or any(kind in rules for kind in KINDS) or rules in {'all', '*'}:
                raise ValueError(f'anti-slop suppression is forbidden: {relative}')
    command = [str(ROOT / 'tools/oxlint/node_modules/.bin/oxlint'),
               '--config', str(ROOT / 'tools/oxlint/oxlintrc.json'),
               '--disable-nested-config', '--no-ignore', '--threads', '2', '-f', 'json', *selected]
    done = subprocess.run(command, cwd=root, capture_output=True, text=True, timeout=120)
    if done.stderr.strip():
        raise ValueError(f'oxlint stderr: {done.stderr.strip()}')
    return parse_output(done.stdout, done.returncode, root, set(selected), sources)


def snapshot_document(findings: Counter, sources: dict) -> dict:
    """Serialize explicit debt for tests/review; intentionally not exposed as a CLI writer."""
    return {'sourceCommit': SOURCE_COMMIT, 'counts': counts(findings), 'findings': [
        {'path': path, 'rule': rule, 'sha256': sha, 'source': sources[(path, rule, sha)], 'count': n}
        for (path, rule, sha), n in sorted(findings.items())]}


def load_snapshot(doc: dict, original: Counter) -> tuple[Counter, dict[str, int]]:
    if not isinstance(doc, dict) or doc.get('sourceCommit') != SOURCE_COMMIT:
        raise ValueError('baseline sourceCommit must remain fixed')
    baseline = doc.get('counts')
    if (not isinstance(baseline, dict) or set(baseline) != set(KINDS)
            or any(type(n) is not int or n < 0 for n in baseline.values())):
        raise ValueError('invalid baseline counts')
    findings = Counter()
    for entry in doc.get('findings', []):
        try:
            key = finding(entry['path'], entry['rule'], entry['source'])
            n = entry['count']
            if (key in findings or entry['sha256'] != key[2] or entry['rule'] not in KINDS
                    or type(n) is not int or n <= 0 or n > original[key]):
                raise ValueError('baseline additions/refreshes are forbidden')
            findings[key] = n
        except (KeyError, TypeError) as error:
            raise ValueError('malformed baseline finding') from error
    if counts(findings) != baseline:
        raise ValueError('baseline counts do not match pinned findings')
    return findings, baseline


def evaluate(current: Counter, debt: Counter, baseline: dict, base: Counter | None,
             added: set[str]) -> tuple[list[str], bool]:
    messages, failed = judge(counts(current), baseline, None if base is None else counts(base), KINDS, BASELINE.name)
    for key, n in sorted(current.items()):
        path, rule, sha = key
        limit = debt[key] if base is None else min(debt[key], base[key])
        if n > limit:
            messages.append(f'FAIL new-source {path} [{rule}] sha256={sha}: {n} > {limit}')
            failed = True
        if path in added:
            messages.append(f'FAIL new-file {path} [{rule}]: {n} violation(s)')
            failed = True
    return messages, failed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-ref', default=os.environ.get('RATCHET_BASE') or None)
    parser.add_argument('--head-ref', default=os.environ.get('RATCHET_HEAD') or 'HEAD')
    args = parser.parse_args()
    started = time.monotonic()
    try:
        # Recompute the immutable original, not the candidate working tree. A
        # baseline refresh cannot authorize a new source, even on main pushes.
        with tree_at(SOURCE_COMMIT, ROOT, SOURCE_ROOTS) as original_root:
            original = scan(original_root)
        debt, baseline = load_snapshot(json.loads(BASELINE.read_text(encoding='utf-8')), original)
        current = scan(ROOT)
        base, added = None, set()
        if args.base_ref:
            with tree_at(args.base_ref, ROOT, SOURCE_ROOTS) as base_root:
                base = scan(base_root)
            added = added_paths(ROOT, args.base_ref, args.head_ref)
            previous = subprocess.run(['git', '-C', str(ROOT), 'show',
                f'{args.base_ref}:tools/ci/anti_slop_lint_baseline.json'], capture_output=True, text=True)
            if previous.returncode == 0:
                old_debt, old_counts = load_snapshot(json.loads(previous.stdout), original)
                if debt - old_debt or any(baseline[k] > old_counts[k] for k in KINDS):
                    raise ValueError('baseline may only decrease vs PR base')
        messages, failed = evaluate(current, debt, baseline, base, added)
        print('\n'.join(messages))
        print(f'anti-slop: {sum(current.values())} findings; elapsed {time.monotonic() - started:.2f}s')
        return int(failed)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f'FAIL anti-slop: {error}')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
