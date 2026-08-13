#!/usr/bin/env python3
# noqa: SIZE_OK — single local-only RTK14 portrait-ingestion CLI.
# Its safety, source, fetch, transform, and report stages share one audited rights boundary.
"""OPENSAM-97 — RTK14 local-only portrait pipeline.

Deterministic observed URLs -> cache-first fetch -> proportional full-frame
resize -> byte-stable report. The transform never changes content selection:
it preserves the whole decoded image, fits it within the requested bounds, and
never enlarges it.

Source and rights boundary:
  * `--manifest` requires `name<TAB>observed_officer_page_url<TAB>` plus the exact
    observed `::attach` URL. It does not construct attachment filenames or URLs.
  * There is no roster/page crawl or downloader. Operators supply both the
    observed manifest and an independently obtained local cache.
  * Queries/fragments (including `?rev`) are removed before cache lookup and
    report output. Raw/cache/output/report paths must be outside the repo or
    gitignored; tracked destinations fail closed.
  * Cache misses are `FAIL/cache_miss`; this program has no network path. A
    manifest or cache entry does not itself establish reuse rights.
  * Source/reuse rights still require separate clearance. This tool neither
    commits, redistributes, nor activates images; fetch/decode/encode failures
    remain `FAIL` and never substitute another image.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import TypedDict

from PIL import Image, UnidentifiedImageError

TOOL_NAME = "opensamguk-rtk-faces"
TOOL_VERSION = "1.4"
DEFAULT_MAX_WIDTH = 156
DEFAULT_MAX_HEIGHT = 210
MAX_SOURCE_BYTES = 64 * 1024 * 1024
MAX_IMAGE_PIXELS = 50_000_000

@dataclass(frozen=True, slots=True)
class Target:
    """One RTK14 officer and its observed-page provenance."""

    name: str
    page_url: str
    observed_image_url: str


class ImageSize(TypedDict):
    width: int
    height: int


class ResizeBounds(TypedDict):
    max_width: int
    max_height: int


class OutputInfo(TypedDict):
    file: str
    width: int
    height: int
    format: str
    fingerprint: str


class ReportEntry(TypedDict):
    name: str
    page_url: str | None
    canonical_url: str | None
    status: str
    reason: str
    source_bytes: int | None
    source_fingerprint: str | None
    source_size: ImageSize | None
    resize: ResizeBounds | None
    output: OutputInfo | None


class ReportCounts(TypedDict):
    OK: int
    FAIL: int


class ReportMeta(TypedDict):
    tool: str
    tool_version: str
    source: str
    provenance: str
    resize_bounds: ResizeBounds
    total: int
    counts: ReportCounts


class Report(TypedDict):
    meta: ReportMeta
    entries: list[ReportEntry]


# --------------------------------------------------------------------------- #
# errors                                                                       #
# --------------------------------------------------------------------------- #
class ManifestError(ValueError):
    """Bad name manifest (empty/duplicate name, missing url, bad row)."""


class FetchError(Exception):
    """Terminal fetch failure. `.reason` is a non-PII code (http_403, timeout)."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


# --------------------------------------------------------------------------- #
# pure helpers                                                                 #
# --------------------------------------------------------------------------- #
def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def strip_query(url: str) -> str:
    """Canonical URL = url with fragment and query (?rev=...) removed."""
    return url.split("#", 1)[0].split("?", 1)[0]


def _fully_unquote(value: str) -> str:
    """Decode until stable so nested escapes cannot hide separators."""
    current = value
    for _ in range(len(value) + 1):
        decoded = urllib.parse.unquote(current)
        if decoded == current:
            return decoded
        current = decoded
    raise ManifestError("url encoding did not converge")


def _parse_observed_page_url(raw_url: str, lineno: int) -> tuple[str, str]:
    """Parse one exact RTK14 officer page URL from an operator observation."""
    parsed = urllib.parse.urlsplit(raw_url)
    if (
        parsed.scheme != "https"
        or parsed.netloc != "wikiwiki.jp"
        or parsed.query
        or parsed.fragment
        or not parsed.path.startswith("/sangokushi14/")
    ):
        raise ManifestError(f"line {lineno}: invalid observed officer page url")
    page_key = parsed.path.removeprefix("/sangokushi14/")
    fully_decoded_key = _fully_unquote(page_key)
    if (
        not page_key
        or fully_decoded_key in {".", ".."}
        or "/" in fully_decoded_key
        or "::" in fully_decoded_key
    ):
        raise ManifestError(f"line {lineno}: invalid observed officer page path")
    return page_key, fully_decoded_key


def _parse_observed_attachment_url(raw_url: str, page_key: str, lineno: int) -> str:
    """Parse an observed original attachment tied to its officer page namespace."""
    canonical_url = strip_query(raw_url)
    parsed = urllib.parse.urlsplit(canonical_url)
    prefix = f"/to/w/sangokushi14/{page_key}/::attach/"
    if (
        parsed.scheme != "https"
        or parsed.netloc != "cdn.wikiwiki.jp"
        or not parsed.path.startswith(prefix)
    ):
        raise ManifestError(
            f"line {lineno}: attachment does not match observed officer page"
        )
    filename = parsed.path.removeprefix(prefix)
    decoded_filename = _fully_unquote(filename)
    if (
        not filename
        or "/" in decoded_filename
        or decoded_filename in {".", ".."}
        or not re.search(r"\.(?:jpg|jpeg|png|gif|webp)$", filename, re.IGNORECASE)
    ):
        raise ManifestError(f"line {lineno}: invalid observed attachment filename")
    return canonical_url


def parse_manifest(text: str) -> list[Target]:
    """Parse a deterministic observed-URL manifest.

    Rows are `name<TAB>observed_officer_page_url<TAB>observed_attachment_url`.
    The attachment must stay in the page's own `::attach` namespace. `?rev` and
    all other query values are stripped before the cache key and report are made.
    """
    rows: list[Target] = []
    seen_page_identities: set[str] = set()
    for lineno, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        parts = s.split("\t")
        if len(parts) != 3:
            raise ManifestError(
                f"line {lineno}: expected "
                "'name<TAB>page_url<TAB>observed_attachment_url'"
            )
        name = parts[0].strip()
        page_url = parts[1].strip()
        attachment_url = parts[2].strip()
        if not name:
            raise ManifestError(f"line {lineno}: empty name")
        if not page_url or not attachment_url:
            raise ManifestError(f"line {lineno}: empty observed url")
        page_key, page_identity = _parse_observed_page_url(page_url, lineno)
        if page_identity in seen_page_identities:
            raise ManifestError(f"line {lineno}: duplicate observed officer page")
        seen_page_identities.add(page_identity)
        canonical_url = _parse_observed_attachment_url(attachment_url, page_key, lineno)
        rows.append(
            Target(
                name=name,
                page_url=page_url,
                observed_image_url=canonical_url,
            )
        )
    rows.sort(key=lambda row: (row.name, row.page_url))
    return rows


def path_is_outside(path: Path, repo_root: Path) -> bool:
    resolved = path.resolve()
    root = repo_root.resolve()
    return resolved != root and root not in resolved.parents


def _git_ignored(path: Path, repo_root: Path) -> bool:
    try:
        r = subprocess.run(
            ["git", "-C", str(repo_root), "check-ignore", "-q", str(path.resolve())],
            capture_output=True,
        )
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def assert_safe_path(path: Path, repo_root: Path, is_ignored=_git_ignored) -> None:
    """Fail closed on repo-tracked targets. Allowed only if the path is outside
    the repo entirely, or inside but provably gitignored."""
    if path_is_outside(path, repo_root):
        return
    if is_ignored(path, repo_root):
        return
    raise SystemExit(
        f"refusing repo-tracked path (must be outside repo or gitignored): {path}"
    )


# --------------------------------------------------------------------------- #
# cache-only source                                                            #
# --------------------------------------------------------------------------- #
class CacheReader:
    """Resolve observed attachment URLs from an operator-supplied local cache."""

    def __init__(self, cache_dir: Path):
        self.cache_dir = cache_dir

    def _cache_path(self, canonical_url: str) -> Path:
        return self.cache_dir / (sha256_hex(canonical_url.encode("utf-8")) + ".bin")

    def fetch(self, canonical_url: str) -> tuple[bytes, bool]:
        """Return cached bytes or a stable failure without network access."""
        canonical_url = strip_query(canonical_url)
        cache_path = self._cache_path(canonical_url)
        no_follow_flag = getattr(os, "O_NOFOLLOW", None)
        non_block_flag = getattr(os, "O_NONBLOCK", None)
        if no_follow_flag is None or non_block_flag is None:
            raise FetchError("cache_unsafe")

        try:
            descriptor = os.open(
                cache_path,
                os.O_RDONLY
                | no_follow_flag
                | non_block_flag,
            )
        except FileNotFoundError as error:
            raise FetchError("cache_miss") from error
        except OSError as error:
            raise FetchError("cache_unsafe") from error

        try:
            with contextlib.ExitStack() as descriptor_stack:
                descriptor_stack.callback(os.close, descriptor)
                if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                    raise FetchError("cache_unsafe")

                source = os.fdopen(descriptor, "rb")
                with source:
                    descriptor_stack.pop_all()
                    if os.fstat(source.fileno()).st_size > MAX_SOURCE_BYTES:
                        raise FetchError("cache_too_large")
                    return source.read(MAX_SOURCE_BYTES + 1), True
        except OSError as error:
            raise FetchError("cache_unsafe") from error


# --------------------------------------------------------------------------- #
# image ops (Pillow; tests may inject a deterministic fake)                    #
# --------------------------------------------------------------------------- #
class PillowImageOps:
    def decode(self, data: bytes) -> Image.Image | None:
        try:
            with Image.open(io.BytesIO(data)) as source:
                if source.width * source.height > MAX_IMAGE_PIXELS:
                    return None
                source.load()
                mode = (
                    "RGBA"
                    if "A" in source.getbands() or "transparency" in source.info
                    else "RGB"
                )
                return source.convert(mode)
        except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError):
            return None

    def size(self, image: Image.Image) -> tuple[int, int]:
        return image.size

    def resize_encode(
        self,
        image: Image.Image,
        max_width: int,
        max_height: int,
    ) -> tuple[bytes, int, int, str]:
        width, height = self.size(image)
        scale = min(1.0, max_width / width, max_height / height)
        out_width = max(1, min(max_width, int(round(width * scale))))
        out_height = max(1, min(max_height, int(round(height * scale))))
        resized = image
        if (out_width, out_height) != (width, height):
            resized = image.resize((out_width, out_height), Image.Resampling.LANCZOS)
        try:
            payload = io.BytesIO()
            resized.save(payload, format="PNG", optimize=False, compress_level=9)
            return payload.getvalue(), out_width, out_height, "png"
        except OSError as error:
            raise FetchError("encode_failed") from error


# --------------------------------------------------------------------------- #
# per-entry processing                                                         #
# --------------------------------------------------------------------------- #
def _entry(name, canonical_url, status, reason, **extra):
    e: ReportEntry = {
        "name": name,
        "page_url": None,
        "canonical_url": canonical_url,
        "status": status,
        "reason": reason,
        "source_bytes": None,
        "source_fingerprint": None,
        "source_size": None,
        "resize": None,
        "output": None,
    }
    e.update(extra)
    return e


def process_target(
    target: Target,
    fetcher,
    image_ops,
    out_dir: Path,
    max_width: int = DEFAULT_MAX_WIDTH,
    max_height: int = DEFAULT_MAX_HEIGHT,
) -> ReportEntry:
    canonical = strip_query(target.observed_image_url)
    base = {"page_url": target.page_url}
    try:
        data, _cached = fetcher.fetch(canonical)
    except FetchError as e:
        return _entry(target.name, canonical, "FAIL", e.reason, **base)

    common = {"source_bytes": len(data), "source_fingerprint": sha256_hex(data), **base}

    img = image_ops.decode(data)
    if img is None:
        return _entry(target.name, canonical, "FAIL", "decode_failed", **common)

    w, h = image_ops.size(img)
    common["source_size"] = {"width": w, "height": h}
    try:
        out_data, ow, oh, fmt = image_ops.resize_encode(img, max_width, max_height)
    except FetchError as e:
        return _entry(target.name, canonical, "FAIL", e.reason, **common)

    out_dir.mkdir(parents=True, exist_ok=True)
    out_fp = sha256_hex(out_data)
    out_name = sha256_hex(canonical.encode("utf-8"))[:16] + "." + fmt
    _atomic_write(out_dir / out_name, out_data)

    return _entry(
        target.name,
        canonical,
        "OK",
        "ok",
        resize={"max_width": max_width, "max_height": max_height},
        output={
            "file": out_name,
            "width": ow,
            "height": oh,
            "format": fmt,
            "fingerprint": out_fp,
        },
        **common,
    )


def build_report(
    targets,
    fetcher,
    image_ops,
    out_dir: Path,
    max_width: int = DEFAULT_MAX_WIDTH,
    max_height: int = DEFAULT_MAX_HEIGHT,
) -> Report:
    entries = [
        process_target(t, fetcher, image_ops, out_dir, max_width, max_height)
        for t in targets
    ]
    entries.sort(
        key=lambda entry: (
            entry["name"],
            entry["page_url"] or "",
            entry["canonical_url"] or "",
        )
    )
    counts: ReportCounts = {
        "OK": sum(e["status"] == "OK" for e in entries),
        "FAIL": sum(e["status"] == "FAIL" for e in entries),
    }
    return {
        "meta": {
            "tool": TOOL_NAME,
            "tool_version": TOOL_VERSION,
            "source": "rtk14/operator-manifest-cache",
            "provenance": "unverified",
            "resize_bounds": {"max_width": max_width, "max_height": max_height},
            "total": len(entries),
            "counts": counts,
        },
        "entries": entries,
    }


def dump_report(report: Report) -> str:
    """Byte-stable serialization: sorted keys, pre-sorted entry list, LF end."""
    return json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _atomic_write(path: Path, data: bytes) -> None:
    """Replace a file atomically without following a pre-existing output symlink."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
        temporary.write(data)
        temporary_path = Path(temporary.name)
    try:
        os.replace(temporary_path, path)
    except OSError:
        temporary_path.unlink(missing_ok=True)
        raise


# --------------------------------------------------------------------------- #
# CLI                                                                          #
# --------------------------------------------------------------------------- #
def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="RTK14 local-only full-portrait resize pipeline")
    p.add_argument(
        "--manifest",
        required=True,
        help=(
            "TSV of name<TAB>observed_page_url<TAB>observed_attachment_url "
            "(outside repo)"
        ),
    )
    p.add_argument("--source-dir", required=True,
                   help="operator-supplied cache root (outside repo or gitignored)")
    p.add_argument("--out-dir", required=True,
                   help="full-image resized output dir (outside repo or gitignored)")
    p.add_argument("--report", required=True,
                   help="report JSON path (outside repo or gitignored)")
    p.add_argument("--limit", type=int, default=None, help="process at most N names")
    p.add_argument("--max-width", type=int, default=DEFAULT_MAX_WIDTH,
                   help=f"maximum output width (default: {DEFAULT_MAX_WIDTH})")
    p.add_argument("--max-height", type=int, default=DEFAULT_MAX_HEIGHT,
                   help=f"maximum output height (default: {DEFAULT_MAX_HEIGHT})")
    args = p.parse_args(argv)
    if args.max_width <= 0 or args.max_height <= 0:
        p.error("--max-width and --max-height must be positive")

    repo = _repo_root()
    source_dir = Path(args.source_dir)
    out_dir = Path(args.out_dir)
    report_path = Path(args.report)
    manifest_path = Path(args.manifest)
    guarded = [manifest_path, source_dir, out_dir, report_path]
    for path in guarded:
        assert_safe_path(path, repo)

    fetcher = CacheReader(source_dir / "cache")
    targets = parse_manifest(manifest_path.read_text(encoding="utf-8"))

    if args.limit is not None:
        targets = targets[: max(0, args.limit)]

    image_ops = PillowImageOps()
    report = build_report(
        targets,
        fetcher,
        image_ops,
        out_dir,
        args.max_width,
        args.max_height,
    )
    _atomic_write(report_path, dump_report(report).encode("utf-8"))

    c = report["meta"]["counts"]
    print(f"total={report['meta']['total']} OK={c['OK']} FAIL={c['FAIL']}")
    print(f"report={report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
