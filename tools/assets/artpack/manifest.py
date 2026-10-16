"""Fail-closed authoring checks for the pinned A2 manifest v1 (see README)."""
import math
import re

SCHEMA_VERSION = 1
ARTIFACT_ID = 'opensamguk-battle-artpack'
ATLAS_MAX = 2048
TERRAIN = 'PFMRW'
TRANSITIONS = 'RFM'
EDGE_BITS = {'NE': 1, 'SE': 2, 'SW': 4, 'NW': 8}
ACTIONS = {'idle': 2, 'move': 4, 'attack': 4, 'hit': 2, 'fall': 4}
FACINGS = ('sw', 'nw', 'ne', 'se')
KEY = re.compile(r'[a-z0-9][a-z0-9_.-]{0,63}\Z')
PNG = re.compile(r'[A-Za-z0-9_.-]+\.png\Z')
SHA = re.compile(r'[0-9a-f]{64}\Z')


def integer(value):
    return type(value) is int


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def validate_manifest(value):
    """Return errors, never coerce malformed containers or silently omit frames.

    Authoring is stricter than A2's structural validator: finite anchors, drawn
    sw/nw, typed containers/provenance and ground-centre anchors are required.
    Every accepted output must also pass the unmodified pinned TS validator.
    """
    errors = []

    def fail(at, reason):
        errors.append(f'{at}: {reason}')

    def record(v, at):
        if not isinstance(v, dict):
            fail(at, 'expected object')
            return {}
        return v

    def array(v, at, minimum=0, length=None):
        if not isinstance(v, list):
            fail(at, 'expected array')
            return []
        if len(v) < minimum or (length is not None and len(v) != length):
            fail(at, f'expected {length if length is not None else f"at least {minimum}"} entries')
        return v

    m = record(value, 'manifest')
    if m.get('schemaVersion') != SCHEMA_VERSION or not integer(m.get('schemaVersion')):
        fail('schemaVersion', 'expected 1')
    if m.get('artifactId') != ARTIFACT_ID:
        fail('artifactId', f'expected {ARTIFACT_ID}')
    if not isinstance(m.get('packId'), str) or not KEY.fullmatch(m['packId']):
        fail('packId', 'lower-case [a-z0-9_.-], 1–64 chars')
    ppb = m.get('pixelsPerBoardPx')
    if not integer(ppb) or not 1 <= ppb <= 4:
        fail('pixelsPerBoardPx', 'integer 1–4')
        ppb = 1
    if m.get('shadow') != 'baked':
        fail('shadow', "v1 accepts 'baked' only")
    atlases = array(m.get('atlases'), 'atlases', minimum=1)
    for i, a in enumerate(atlases):
        a = record(a, f'atlases[{i}]')
        if not isinstance(a.get('file'), str) or not PNG.fullmatch(a['file']):
            fail(f'atlases[{i}].file', 'plain *.png name')
        for side in ('width', 'height'):
            if not integer(a.get(side)) or not 1 <= a[side] <= ATLAS_MAX:
                fail(f'atlases[{i}].{side}', 'integer 1–2048')
        if 'sha256' in a and (not isinstance(a['sha256'], str) or not SHA.fullmatch(a['sha256'])):
            fail(f'atlases[{i}].sha256', '64 lower-case hex')
    files = [a.get('file') for a in atlases if isinstance(a, dict) and isinstance(a.get('file'), str)]
    if len(files) != len(set(files)):
        fail('atlases', 'duplicate file name')

    def frame(f, at, ground=False):
        f = record(f, at)
        if not all(integer(f.get(k)) for k in ('atlas', 'x', 'y', 'w', 'h')):
            fail(at, 'integer rect required')
            return f
        if min(f['x'], f['y'], f['atlas']) < 0 or min(f['w'], f['h']) < 1:
            fail(at, 'nonnegative position/index, positive size required')
        a = atlases[f['atlas']] if 0 <= f['atlas'] < len(atlases) else None
        if not isinstance(a, dict):
            fail(at, 'atlas does not exist')
        elif all(integer(a.get(s)) for s in ('width', 'height')) and (f['x'] + f['w'] > a['width'] or f['y'] + f['h'] > a['height']):
            fail(at, 'outside atlas')
        if not all(number(f.get(k)) and 0 <= f[k] <= f[s] for k, s in (('ax', 'w'), ('ay', 'h'))):
            fail(at, 'finite anchor inside frame required')
        if ground:
            if f['w'] != 32 * ppb or f['h'] < 16 * ppb:
                fail(at, 'ground width = 32*ppb, height >= 16*ppb')
            # Taller source images may have headroom; their diamond is bottom-aligned.
            if f.get('ax') != 16 * ppb or f.get('ay') != f['h'] - 8 * ppb:
                fail(at, 'ground anchor must be bottom-aligned diamond centre')
        return f

    terrain = record(m.get('terrain'), 'terrain')
    for letter in TERRAIN:
        at = f'terrain.{letter}'
        t = record(terrain.get(letter), at)
        for i, f in enumerate(array(t.get('ground'), at + '.ground', minimum=1)):
            frame(f, f'{at}.ground[{i}]', True)
        if 'tall' in t:
            for i, f in enumerate(array(t['tall'], at + '.tall')):
                frame(f, f'{at}.tall[{i}]')
        if 'tallByEdges' in t:
            for i, f in enumerate(array(t['tallByEdges'], at + '.tallByEdges', length=16)):
                if f is not None:
                    frame(f, f'{at}.tallByEdges[{i}]')
    for letter, frames in record(m.get('transitions', {}), 'transitions').items():
        if letter not in TRANSITIONS or len(letter) != 1:
            fail('transitions.' + letter, 'only R/F/M')
        for i, f in enumerate(array(frames, 'transitions.' + letter, length=16)):
            if i == 0 and f is not None:
                fail('transitions.' + letter, 'index 0 must be null')
            if f is not None:
                frame(f, f'transitions.{letter}[{i}]', True)
    for slot, unit in record(m.get('units'), 'units').items():
        at = 'units.' + slot
        if not KEY.fullmatch(slot):
            fail(at, 'invalid slot key')
        actions = record(record(unit, at).get('actions'), at + '.actions')
        if 'idle' not in actions:
            fail(at, 'idle required')
        for action, definition in actions.items():
            where = at + '.' + action
            d = record(definition, where)
            count = d.get('frames')
            if action not in ACTIONS or not integer(count) or not 1 <= count <= ACTIONS.get(action, 0):
                fail(where, 'unknown action or invalid frame count')
            directions = record(d.get('directions'), where + '.directions')
            for facing in ('sw', 'nw'):
                if not isinstance(directions.get(facing), dict) or 'mirror' in directions[facing]:
                    fail(where + '.' + facing, 'drawn direction required')
            for facing, direction in directions.items():
                loc = where + '.' + facing
                if facing not in FACINGS:
                    fail(loc, 'unknown direction')
                direction = record(direction, loc)
                if 'mirror' in direction:
                    target = directions.get(direction['mirror']) if isinstance(direction['mirror'], str) else None
                    if not isinstance(target, dict) or 'mirror' in target:
                        fail(loc, 'mirror must name a drawn direction')
                    if set(direction) != {'mirror'}:
                        fail(loc, 'mirror cannot include frames/masks')
                    continue
                frames = array(direction.get('frames'), loc, length=count if integer(count) else 0)
                bodies = [frame(f, f'{loc}[{i}]') for i, f in enumerate(frames)]
                if 'masks' in direction:
                    for i, mask in enumerate(array(direction['masks'], loc + '.masks', length=len(frames))):
                        mask = frame(mask, f'{loc}.masks[{i}]')
                        if i < len(bodies) and any(mask.get(k) != bodies[i].get(k) for k in ('w', 'h')):
                            fail(loc + '.masks', 'same size as frame required')
    for i, p in enumerate(array(m.get('provenance'), 'provenance', minimum=1)):
        p = record(p, f'provenance[{i}]')
        if p.get('source') not in ('procedural', 'generated', 'hand'):
            fail(f'provenance[{i}]', 'invalid source')
        if not isinstance(p.get('note'), str):
            fail(f'provenance[{i}]', 'note required')
        if p.get('source') == 'generated' and not all(isinstance(p.get(k), str) and p[k].strip() for k in ('model', 'prompt', 'createdAt')):
            fail(f'provenance[{i}]', 'generated needs model, prompt, createdAt')
    return errors


def require_manifest(value):
    errors = validate_manifest(value)
    if errors:
        raise ValueError('; '.join(errors[:12]))
    return value
