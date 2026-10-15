#!/usr/bin/env python3
"""Read-only HWIHA ledger inventory. No network, database or Kotlin execution.

This deliberately small Spring/Kotlin mapping reader supports literal paths, arrays,
class prefixes and RequestMethod lists. Unsupported mapping syntax fails closed;
this is a drift detector, not a Kotlin compiler or a runtime correctness proof.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

API_ROOT = Path('app/game-api/src/main/kotlin')
CATALOG = Path('data/commands/input-catalog.json')
LEDGER = Path('docs/api/hwiha-consistency-terminal-contract.md')
BEGIN = '<!-- HWIHA-TERMINAL-LEDGER:BEGIN -->'
END = '<!-- HWIHA-TERMINAL-LEDGER:END -->'
CLASSES = {'AUTHORITATIVE', 'RYW', 'EVENTUAL', 'Mutation', 'NON_STATE'}
STATUSES = {'CURRENT', 'TARGET', 'NOT_DELIVERED', 'UNKNOWN'}
METHODS = {'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS', 'TRACE', 'ANY'}
# Keep quoted strings intact while removing comments (including fake annotations).
LEX = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*')
TOKEN = re.compile(r'@(?:[\w]+\.)*(Get|Post|Put|Patch|Delete|Request|Head|Options|Trace)Mapping\b|\b(class|fun)\s+\w+')
STRING = r'"(?:\\.|[^"\\])*"'


def string(value):
    return isinstance(value, str) and bool(value.strip())


def strings(value):
    return isinstance(value, list) and all(string(v) for v in value) and len(set(value)) == len(value)


def local_path(value):
    return string(value) and not Path(value).is_absolute() and '..' not in Path(value).parts


def schema_errors(data):
    errors = []

    def require(ok, label):
        if not ok:
            errors.append('R5: ' + label)

    def route(value, label, exclusion=False):
        if not isinstance(value, dict):
            require(False, label + ' must be an object')
            return
        require(string(value.get('method')) and value['method'] in METHODS, label + '.method invalid')
        require(string(value.get('path')) and value['path'].startswith('/'), label + '.path invalid')
        require(local_path(value.get('controller')), label + '.controller invalid')
        if exclusion:
            require(string(value.get('reason')), label + '.reason required')

    if not isinstance(data, dict):
        return ['R5: ledger must be an object']
    require(data.get('schemaVersion') == 1, 'schemaVersion must be 1')
    for key in ('families', 'inputOverrides', 'outOfScopeMutationRoutes', 'gaps'):
        require(isinstance(data.get(key), list), key + ' must be an array')
    if errors:
        return errors
    require(bool(data['families']), 'families must not be empty')
    for i, family in enumerate(data['families']):
        label = f'families[{i}]'
        if not isinstance(family, dict):
            require(False, label + ' must be an object')
            continue
        for key in ('id', 'effectTiming', 'invalidation'):
            require(string(family.get(key)), label + '.' + key + ' required')
        for key in ('inputIds', 'kinds', 'phases', 'deliveryStates'):
            require(strings(family.get(key)), label + '.' + key + ' must be unique strings')
        require(string(family.get('consistencyClass')) and family['consistencyClass'] in CLASSES, label + '.consistencyClass invalid')
        require(string(family.get('status')) and family['status'] in STATUSES, label + '.status invalid')
        admission = family.get('admission')
        require(isinstance(admission, dict) and strings(admission.get('currentTypes')) and
                string(admission.get('target')), label + '.admission currentTypes/target required')
        execution = family.get('execution')
        require(isinstance(execution, dict) and
                (execution.get('currentTypes') is None or strings(execution.get('currentTypes'))) and
                'currentTypes' in execution and string(execution.get('reason')),
                label + '.execution currentTypes and reason required (also for null)')
        for key in ('routes', 'anchors'):
            require(isinstance(family.get(key), list), label + '.' + key + ' must be an array')
        for j, value in enumerate(family.get('routes', []) if isinstance(family.get('routes'), list) else []):
            route(value, f'{label}.routes[{j}]')
        anchors = family.get('anchors', [])
        if isinstance(anchors, list):
            require(bool(anchors), label + '.anchors must not be empty')
            for anchor in anchors:
                require(isinstance(anchor, dict) and local_path(anchor.get('path')) and
                        string(anchor.get('literal')), label + '.anchors path/literal required')
    for value in data['inputOverrides']:
        require(isinstance(value, dict) and string(value.get('inputId')) and
                string(value.get('familyId')), 'inputOverrides inputId/familyId required')
    for i, value in enumerate(data['outOfScopeMutationRoutes']):
        route(value, f'outOfScopeMutationRoutes[{i}]', exclusion=True)
    for gap in data['gaps']:
        require(isinstance(gap, dict) and all(string(gap.get(k)) for k in
                ('id', 'current', 'target', 'followup')) and isinstance(gap.get('unknown'), bool),
                'gaps id/current/target/followup/unknown required')
    return errors


def split_args(text):
    """Split top-level annotation arguments, preserving bracket/paren nesting."""
    parts, start, depth, quoted, escaped = [], 0, 0, False, False
    for i, char in enumerate(text):
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in '[(':
            depth += 1
        elif char in '])':
            depth -= 1
        elif char == ',' and depth == 0:
            parts.append(text[start:i].strip())
            start = i + 1
    parts.append(text[start:].strip())
    return [p for p in parts if p]


def literal_paths(value, constants):
    value = value.strip()
    if value.startswith('[') and value.endswith(']'):
        values = split_args(value[1:-1])
    else:
        values = [value]
    values = [constants.get(v, v) for v in values]
    if not values or any(not re.fullmatch(STRING, v) or '$' in v for v in values):
        raise ValueError('non-literal or empty mapping path: ' + value)
    return [json.loads(v) for v in values]


def mapping_arguments(kind, args, constants):
    paths, methods = [''], [kind.upper() if kind != 'Request' else 'ANY']
    has_path = False
    for part in split_args(args):
        if '=' not in part or part.lstrip().startswith('"'):
            key, value = 'value', part
        else:
            key, value = (v.strip() for v in part.split('=', 1))
        if key in {'value', 'path'}:
            if has_path:
                raise ValueError('multiple path/value arguments')
            paths, has_path = literal_paths(value, constants), True
        elif key == 'method':
            value = value.strip().strip('[]')
            values = split_args(value)
            if not values or any(not re.fullmatch(r'(?:[\w]+\.)*RequestMethod\.[A-Z]+', v) for v in values):
                raise ValueError('unsupported RequestMapping method: ' + value)
            methods = [v.rsplit('.', 1)[1] for v in values]
            if any(v not in METHODS - {'ANY'} for v in methods):
                raise ValueError('unknown RequestMethod')
        elif key not in {'produces', 'consumes', 'headers', 'params', 'name'}:
            raise ValueError('unsupported mapping argument: ' + key)
    return paths, methods


def read_mappings(root):
    routes, errors = [], []
    if not (root / API_ROOT).is_dir():
        return [], ['R3: game-api Kotlin source directory missing']
    for file in sorted((root / API_ROOT).rglob('*.kt')):
        path = file.relative_to(root).as_posix()
        source = LEX.sub(lambda m: ' ' if m[0].startswith(('//', '/*')) else m[0],
                         file.read_text(encoding='utf-8'))
        pending, scopes = [], []
        constants = dict(re.findall(r'\bconst\s+val\s+(\w+)\s*=\s*(' + STRING + r')', source))
        # Mask strings for declaration scanning, but read annotation args from original source.
        scan = LEX.sub(lambda m: ' ' * len(m[0]), source)
        consumed = 0
        for match in TOKEN.finditer(scan):
            if match.start() < consumed:
                continue
            if match.group(1):
                kind, pos = match.group(1), match.end()
                while pos < len(source) and source[pos].isspace():
                    pos += 1
                args = ''
                if pos < len(source) and source[pos] == '(':
                    start, depth, quoted, escaped = pos + 1, 1, False, False
                    pos += 1
                    while pos < len(source) and depth:
                        char = source[pos]
                        if quoted:
                            if escaped:
                                escaped = False
                            elif char == '\\':
                                escaped = True
                            elif char == '"':
                                quoted = False
                        elif char == '"':
                            quoted = True
                        elif char == '(':
                            depth += 1
                        elif char == ')':
                            depth -= 1
                        pos += 1
                    if depth:
                        errors.append(f'R3: {path}: unclosed mapping annotation')
                        continue
                    args, consumed = source[start:pos - 1], pos
                try:
                    pending.append(mapping_arguments(kind, args, constants))
                except ValueError as error:
                    errors.append(f'R3: {path}: {error}')
            elif match.group(2) == 'class':
                if len(pending) > 1:
                    errors.append(f'R3: {path}: multiple class mapping annotations unsupported')
                if pending:
                    opening = scan.find('{', match.end())
                    if opening < 0:
                        errors.append(f'R3: {path}: mapped class body missing')
                    else:
                        depth, closing = 1, opening + 1
                        while closing < len(scan) and depth:
                            if scan[closing] == '{':
                                depth += 1
                            elif scan[closing] == '}':
                                depth -= 1
                            closing += 1
                        if depth:
                            errors.append(f'R3: {path}: unclosed mapped class body')
                        if pending[0][1] != ['ANY']:
                            errors.append(f'R3: {path}: class-level method restriction unsupported')
                        scopes.append((opening, closing, pending[0][0]))
                pending = []
            else:
                enclosing = [s for s in scopes if s[0] < match.start() < s[1]]
                prefixes = enclosing[-1][2] if enclosing else ['']
                for paths, methods in pending:
                    for prefix in prefixes:
                        for suffix in paths:
                            full = '/' + '/'.join(v.strip('/') for v in (prefix, suffix) if v.strip('/'))
                            for method in methods:
                                routes.append({'method': method, 'path': full, 'controller': path})
                pending = []
        if pending:
            errors.append(f'R3: {path}: mapping has no class/function declaration')
    return routes, errors


def route_key(route):
    return route['method'], route['path'], route['controller']


def check_root(root):
    root = Path(root)
    result = {'violations': [], 'assignments': {}, 'familyCounts': {}, 'mutationRoutes': []}
    errors = result['violations']
    try:
        text = (root / LEDGER).read_text(encoding='utf-8')
        if text.count(BEGIN) != 1 or text.count(END) != 1:
            raise ValueError('exactly one ledger marker pair required')
        block = text.split(BEGIN)[1].split(END)[0]
        match = re.fullmatch(r'\s*```json\s*\n([\s\S]*?)\n```\s*', block)
        if not match:
            raise ValueError('one JSON fenced block required')
        ledger = json.loads(match[1])
        errors.extend(schema_errors(ledger))
        if errors:
            return result
        catalog = json.loads((root / CATALOG).read_text(encoding='utf-8'))
        entries = catalog['inputs']
        if not isinstance(entries, list) or not all(isinstance(e, dict) and
                all(string(e.get(k)) for k in ('inputId', 'kind', 'deliveryState')) and
                isinstance(e.get('timing'), dict) and string(e['timing'].get('phase')) for e in entries):
            raise ValueError('catalog inputs require inputId/kind/deliveryState/timing.phase')
    except (OSError, ValueError, KeyError, TypeError) as error:
        errors.append('R5: cannot read ledger/catalog: ' + str(error))
        return result
    families = ledger['families']
    ids = [f['id'] for f in families]
    if len(ids) != len(set(ids)):
        errors.append('R5: duplicate family id')
    family_map = {f['id']: f for f in families}
    known_kinds = {k for f in families for k in f['kinds']}
    known_phases = {p for f in families for p in f['phases']}
    overrides = {}
    for override in ledger['inputOverrides']:
        key = override['inputId']
        if key in overrides:
            errors.append('R1: duplicate override ' + key)
        if override['familyId'] not in family_map:
            errors.append('R1: unknown override family for ' + key)
        overrides[key] = override['familyId']
    override_families = set(overrides.values())
    input_ids = [e['inputId'] for e in entries]
    for key, count in Counter(input_ids).items():
        if count != 1:
            errors.append('R1: duplicate catalog inputId ' + key)
    members = {}
    for family in families:
        for key in family['inputIds']:
            members.setdefault(key, []).append(family['id'])
    for key in sorted(set(members) - set(input_ids)):
        errors.append('R1: stale ledger inputId ' + key)
    for key in sorted(set(overrides) - set(input_ids)):
        errors.append('R1: stale override ' + key)
    for entry in entries:
        key, kind, phase = entry['inputId'], entry['kind'], entry['timing']['phase']
        if kind not in known_kinds or phase not in known_phases:
            errors.append(f'R2: {key}: unknown kind/phase {kind}/{phase}')
        selected = members.get(key, [])
        if len(selected) != 1:
            errors.append(f'R1: {key}: expected one explicit inventory member, found {len(selected)}')
            continue
        matching = [f['id'] for f in families if kind in f['kinds'] and
                    phase in f['phases'] and entry['deliveryState'] in f['deliveryStates']]
        if key in overrides:
            matching = [v for v in matching if v == overrides[key]]
        else:
            matching = [v for v in matching if v not in override_families]
        if matching != selected:
            errors.append(f'R1: {key}: tuple/override mismatch')
            continue
        family = family_map[selected[0]]
        if entry['deliveryState'] == 'PLANNED' and family['status'] != 'NOT_DELIVERED':
            errors.append(f'R1: {key}: PLANNED must be NOT_DELIVERED')
            continue
        result['assignments'][key] = family['id']
    result['familyCounts'] = {family_id: list(result['assignments'].values()).count(family_id)
                              for family_id in sorted(family_map)}
    routes, mapping_errors = read_mappings(root)
    errors.extend(mapping_errors)
    actual = Counter(route_key(r) for r in routes if r['method'] != 'GET')
    declared_routes = [r for f in families for r in f['routes']]
    excluded = ledger['outOfScopeMutationRoutes']
    declared = Counter(route_key(r) for r in declared_routes + excluded if r['method'] != 'GET')
    for key in sorted(actual.keys() | declared.keys()):
        if actual[key] != 1 or declared[key] != 1:
            errors.append(f'R3: {key}: source={actual[key]}, ledger={declared[key]} (expected 1 each)')
    all_actual = {route_key(r) for r in routes}
    for route in declared_routes:
        if route['method'] == 'GET' and route_key(route) not in all_actual:
            errors.append(f'R3: stale declared read route {route_key(route)}')
    result['mutationRoutes'] = [dict(zip(('method', 'path', 'controller'), key)) for key in sorted(actual)]
    for family in families:
        for anchor in family['anchors']:
            try:
                content = (root / anchor['path']).read_text(encoding='utf-8')
            except OSError:
                content = ''
            if anchor['literal'] not in content:
                errors.append(f"R4: {family['id']}: missing literal {anchor['literal']!r} in {anchor['path']}")
    result['assignments'] = dict(sorted(result['assignments'].items()))
    result['violations'] = sorted(set(errors))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', required=True)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args(argv)
    result = check_root(args.root)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print('HWIHA terminal inventory: ' + ('FAIL' if result['violations'] else 'PASS'))
        print(f"inputs={len(result['assignments'])}; mutationRoutes={len(result['mutationRoutes'])}")
        for family, count in result['familyCounts'].items():
            print(f'{family}: {count}')
        for key, family in result['assignments'].items():
            print(f'{key} -> {family}')
        for error in result['violations']:
            print(error)
    return int(bool(result['violations']))


if __name__ == '__main__':
    raise SystemExit(main())
