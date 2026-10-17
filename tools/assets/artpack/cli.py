"""Deterministic, lossless PNG shelf packing; python3 -m tools.assets.artpack."""
import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile

from PIL import Image
from tools.assets.check_sprite_chroma import bad
from .manifest import ATLAS_MAX, require_manifest, integer


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f'duplicate JSON key: {key}')
            result[key] = value
        return result

    def invalid(value):
        raise ValueError(f'non-finite JSON value: {value}')

    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique, parse_constant=invalid)


def safe_file(root, name):
    if not isinstance(name, str) or not name or Path(name).is_absolute() or '\\' in name or '..' in Path(name).parts:
        raise ValueError('source must be a relative PNG path without traversal')
    path = (root / name).resolve()
    if not path.is_relative_to(root.resolve()) or path.suffix.lower() != '.png':
        raise ValueError('PNG path escapes the input directory')
    return path


def png(path):
    # Check header size before decoding; source frames never exceed one atlas.
    with Image.open(path) as image:
        if image.format != 'PNG' or image.mode != 'RGBA' or getattr(image, 'n_frames', 1) != 1:
            raise ValueError(f'{path.name}: single-frame RGBA PNG required')
        if not 1 <= image.width <= ATLAS_MAX or not 1 <= image.height <= ATLAS_MAX:
            raise ValueError(f'{path.name}: size must be 1–2048')
        image.load()
        return image.copy()


def frame_refs(m):
    """Only contract frame positions; never walk provenance or arbitrary keys."""
    for t in m['terrain'].values():
        for key in ('ground', 'tall', 'tallByEdges'):
            for f in t.get(key, []):
                if f is not None:
                    yield f, False
    for frames in m.get('transitions', {}).values():
        for f in frames:
            if f is not None:
                yield f, False
    for unit in m['units'].values():
        for action in unit['actions'].values():
            for direction in action['directions'].values():
                if 'mirror' not in direction:
                    for key in ('frames', 'masks'):
                        for f in direction.get(key, []):
                            yield f, key == 'masks'


def assert_grey(image):
    # Invisible RGB is irrelevant, but every visible mask pixel must be grey.
    if any(a and not r == g == b for r, g, b, a in image.get_flattened_data()):
        raise ValueError('faction mask must be grayscale RGBA')


def check_pack(manifest_path):
    path = Path(manifest_path).resolve()
    m = require_manifest(read_json(path))
    images = []
    for atlas in m['atlases']:
        file = safe_file(path.parent, atlas['file'])
        raw = file.read_bytes()
        if 'sha256' in atlas and digest(raw) != atlas['sha256']:
            raise ValueError(f'{atlas["file"]}: sha256 differs from manifest')
        image = png(file)
        if image.size != (atlas['width'], atlas['height']):
            raise ValueError(f'{atlas["file"]}: PNG dimensions differ from manifest')
        images.append(image)
    for f, mask in frame_refs(m):
        if mask:
            assert_grey(images[f['atlas']].crop((f['x'], f['y'], f['x'] + f['w'], f['y'] + f['h'])))
    return m


def build_pack(spec_path, output, atlas_size=ATLAS_MAX):
    if not integer(atlas_size) or not 1 <= atlas_size <= ATLAS_MAX:
        raise ValueError('atlas-size: integer 1–2048 required')
    spec_path = Path(spec_path).resolve()
    spec = read_json(spec_path)
    if not isinstance(spec, dict) or 'atlases' in spec:
        raise ValueError('source spec must be a manifest template without atlases')
    manifest = copy.deepcopy(spec)
    images = {}
    refs = []
    # Replace source descriptors with temporary frame rectangles, then run full
    # shape validation before traversal. Unexpected nested data fail cleanly.
    def source(value):
        if not isinstance(value, dict):
            return value
        if 'file' in value:
            if set(value) != {'file', 'ax', 'ay'}:
                raise ValueError('source frame requires exactly file, ax, ay')
            file = value['file']
            path = safe_file(spec_path.parent, file)
            if file not in images:
                images[file] = png(path)
            image = images[file]
            f = dict(atlas=0, x=0, y=0, w=image.width, h=image.height, ax=value['ax'], ay=value['ay'])
            refs.append((file, f))
            return f
        return {key: transform(child) for key, child in value.items()}

    def transform(value):
        if isinstance(value, dict):
            return source(value)
        if isinstance(value, list):
            return [transform(child) for child in value]
        return value

    # Only transform contract sections, leaving provenance as literal metadata.
    for key in ('terrain', 'transitions', 'units'):
        if key in manifest:
            manifest[key] = transform(manifest[key])
    manifest['atlases'] = [dict(file='atlas-000.png', width=ATLAS_MAX, height=ATLAS_MAX)]
    require_manifest(manifest)
    # Mixing final atlas rects with source descriptors could silently point at
    # unrelated pixels, so every referenced frame must originate from a source.
    frame_ids = {id(f) for _, f in refs}
    for f, mask in frame_refs(manifest):
        if id(f) not in frame_ids:
            raise ValueError('every frame must use a source file descriptor')
        if mask:
            file = next(name for name, ref in refs if ref is f)
            assert_grey(images[file])
    placements = {}
    pages = []
    x = y = row_h = used_w = used_h = 0
    page_files = []

    def finish():
        if page_files:
            pages.append((used_w, used_h, list(page_files)))

    # Stable lexicographic source names, independent of JSON insertion order,
    # mtime, current directory, output path or filesystem enumeration order.
    for file in sorted(images):
        image = images[file]
        w, h = image.size
        if w > atlas_size or h > atlas_size:
            raise ValueError(f'{file}: frame exceeds atlas-size {atlas_size}')
        if x + w > atlas_size:
            x, y, row_h = 0, y + row_h, 0
        if y + h > atlas_size:
            finish()
            x = y = row_h = used_w = used_h = 0
            page_files = []
        placements[file] = (len(pages), x, y)
        page_files.append(file)
        used_w, used_h = max(used_w, x + w), max(used_h, y + h)
        x, row_h = x + w, max(row_h, h)
    finish()
    for file, f in refs:
        f['atlas'], f['x'], f['y'] = placements[file]
    encoded = []
    manifest['atlases'] = []
    for index, (w, h, files) in enumerate(pages):
        canvas = Image.new('RGBA', (w, h))
        for file in files:
            _, px, py = placements[file]
            # No alpha compositing: preserve source RGBA, including semi-alpha.
            canvas.paste(images[file], (px, py))
        data = io.BytesIO()
        canvas.save(data, format='PNG', compress_level=9, optimize=False)
        raw = data.getvalue()
        name = f'atlas-{index:03d}.png'
        manifest['atlases'].append(dict(file=name, width=w, height=h, sha256=digest(raw)))
        encoded.append((name, raw))
    require_manifest(manifest)
    target = Path(output).absolute()
    if target.exists() or target.is_symlink():
        raise ValueError('output already exists; choose a new directory')
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix='.artpack-', dir=target.parent))
    try:
        for name, raw in encoded:
            (staging / name).write_bytes(raw)
        (staging / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False) + '\n', encoding='utf-8')
        check_pack(staging / 'manifest.json')
        # rename fails for nonempty output; never remove or overwrite a pack.
        if target.exists() or target.is_symlink():
            raise ValueError('output appeared while building')
        os.rename(staging, target)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return manifest


def check_chroma(path):
    # The legacy six-point check needs a minimum 5×5 source. No pixels change.
    with Image.open(path) as image:
        if min(image.size) < 5:
            raise ValueError('chroma source must be at least 5×5')
    reason = bad(Path(path))
    if reason:
        raise ValueError(reason)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    build = commands.add_parser('build', help='pack pre-authored PNGs without editing')
    build.add_argument('spec', type=Path)
    build.add_argument('output', type=Path)
    build.add_argument('--atlas-size', type=int, default=ATLAS_MAX)
    check = commands.add_parser('check', help='validate manifest, PNGs, hashes and masks')
    check.add_argument('manifest', type=Path)
    chroma = commands.add_parser('check-chroma', help='reuse existing raw chroma inspection')
    chroma.add_argument('png', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'build':
            build_pack(args.spec, args.output, args.atlas_size)
        elif args.command == 'check':
            check_pack(args.manifest)
        else:
            check_chroma(args.png)
    except (ValueError, OSError, TypeError, KeyError, AttributeError, Image.DecompressionBombError) as error:
        parser.exit(1, f'artpack: {error}\n')
    print('artpack: OK')
    return 0
