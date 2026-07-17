#!/usr/bin/env python3
# noqa: SIZE_OK — single local-only RTK14 portrait-ingestion CLI.
# Its safety, source, fetch, transform, and report stages share one audited rights boundary.
"""OPENSAM-97 — RTK14 local-only portrait pipeline.

Deterministic name enumeration -> URL ?rev/query stripping -> polite
fetch/cache -> proportional full-image resize -> byte-stable report.

Rights posture (see docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-
execution-contract.md section 7 / D4):
  * RTK14 ONLY. Never widen to RTK8R or another game.
  * raw / cache / resized-output / report paths MUST be outside the repo or gitignored.
    Repo-tracked targets fail closed.
  * On fetch/decode/encode failure: record FAIL. NEVER substitute another image.
    Portrait URLs are DERIVED from observed roster fields, never invented:
    a wrong derivation 404s (honest FAIL), never a wrong-officer face.
  * Nothing here commits, redistributes, or activates any image.

Source facts extracted from research (recorded [사실]):
  * Name/portrait source family: wikiwiki.jp/sangokushi14 (RTK14 community wiki).
    Roster = 史実武将 page; each officer row is a rel-wiki-page name cell + a
    katakana reading cell. Officer detail pages: .../sangokushi14/<urlencoded-name>.
    -- docs/.../2026-07-17-opensam-91b-npc-portrait-data-pool.md
  * Portrait is a CDN attachment in the officer's OWN namespace, filename == the
    officer's katakana reading (confirmed: 曹操 -> ソウソウ.jpg):
    https://cdn.wikiwiki.jp/to/w/sangokushi14/<page>/::attach/<reading>.jpg?rev=<hash>&t=<ts>
    -- docs/.../2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md

Portrait discovery (two modes; both fetch only real observed sources):
  * 'reading' (default): construct the ::attach URL from the roster's reading
    column and fetch it from the CDN (which is not rate-limited). Per-officer
    namespaced -> can only resolve to THAT officer; wrong reading/ext 404s.
  * 'page': fetch each officer page and parse its ::attach portrait attachment.
    Faithful, but the wiki page host rate-limits (429) sustained crawls.
  Manifest mode (--names name<TAB>image_url) remains for offline determinism.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import namedtuple
from pathlib import Path
from typing import TypedDict

TOOL_NAME = "opensamguk-rtk-faces"
TOOL_VERSION = "1.2"
DEFAULT_MAX_WIDTH = 156
DEFAULT_MAX_HEIGHT = 210
DEFAULT_USER_AGENT = (
    f"{TOOL_NAME}/{TOOL_VERSION} "
    "(local RTK14 research tool; +https://github.com/peppone-choi/opensamguk)"
)
MIN_DELAY_SECONDS = 1.0

# [사실] RTK14 community wiki. Roster = 史実武将 list page; each officer links to
# https://wikiwiki.jp/sangokushi14/<urlencoded-name>. Portrait is a cdn attachment
# in that officer's namespace: .../sangokushi14/<page>/::attach/<katakana>.jpg?rev=...
# (::attach = faithful original; ::ref = possible .webp derivative).
WIKI_ORIGIN = "https://wikiwiki.jp"
DEFAULT_ROSTER_URL = "https://wikiwiki.jp/sangokushi14/%E5%8F%B2%E5%AE%9F%E6%AD%A6%E5%B0%86"

# name -> fetch url. kind "image": url is the portrait itself (manifest mode).
# kind "page": url is the officer page; portrait is discovered from its HTML.
Target = namedtuple("Target", ["name", "url", "kind", "page_key"])


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


class _PermanentHttp(Exception):
    def __init__(self, code: int):
        self.code = code


class _Transient(Exception):
    def __init__(self, reason: str, retry_after: float = 0.0):
        self.reason = reason
        self.retry_after = retry_after


# --------------------------------------------------------------------------- #
# pure helpers                                                                 #
# --------------------------------------------------------------------------- #
def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def strip_query(url: str) -> str:
    """Canonical URL = url with fragment and query (?rev=...) removed."""
    return url.split("#", 1)[0].split("?", 1)[0]


def parse_manifest(text: str) -> list[tuple[str, str, str]]:
    """Deterministic enumeration of the fixed RTK14 name source.

    Rows: `name<TAB>image_url`. `#` comments and blank lines are ignored.
    Rejects empty names, empty urls, and duplicate names. Result is sorted by
    name so two runs over the same source yield identical order/count and
    identical normalized (query-stripped) URLs.
    """
    rows: list[tuple[str, str, str]] = []
    seen: set[str] = set()
    for lineno, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        parts = s.split("\t")
        if len(parts) < 2:
            raise ManifestError(f"line {lineno}: expected 'name<TAB>url'")
        name = parts[0].strip()
        url = parts[1].strip()
        if not name:
            raise ManifestError(f"line {lineno}: empty name")
        if not url:
            raise ManifestError(f"line {lineno}: empty url")
        if name in seen:
            raise ManifestError(f"line {lineno}: duplicate name")
        seen.add(name)
        rows.append((name, url, strip_query(url)))
    rows.sort(key=lambda r: r[0])
    return rows


# Officer data row in the 史実武将 table: a rel-wiki-page name cell followed by the
# reading (katakana) cell. The name link + adjacent reading cell together exclude
# menubar/guide links (FAQ, おまけ武将, …) that are not officer rows.
_ROSTER_ROW = re.compile(
    r'<td[^>]*><a href="/sangokushi14/([^"?#]+)"[^>]*class="rel-wiki-page"[^>]*>([^<]*)</a></td>'
    r'\s*<td[^>]*>([^<]*)</td>'
)


def parse_roster(html_text: str) -> list[tuple[str, str, str]]:
    """Deterministic officer enumeration from the 史実武将 roster page.

    An officer row is a rel-wiki-page name cell followed by its reading cell
    (that td + adjacent-reading structure is what excludes menu/guide links).
    Returns (name, page_encoded, reading) sorted by name.

    The DISPLAY name is the visible link text (true kanji). The page name is NOT
    the display name: the wiki substitutes katakana for rare kanji in page names
    (龐統 -> page ホウ統, 賈詡 -> 賈ク), so keying the name off the page dropped ~98
    real officers. The page_encoded (katakana or not) still addresses the CDN
    namespace; the reading (katakana) is the portrait attachment stem.
    """
    seen: set[str] = set()
    rows: list[tuple[str, str, str]] = []
    for enc, text, reading in _ROSTER_ROW.findall(html_text):
        if "::" in enc or "/" in urllib.parse.unquote(enc):
            continue
        name = html.unescape(text.strip())
        if not name or enc in seen:  # dedup by page (the roster repeats each link)
            continue
        seen.add(enc)
        rows.append((name, enc, html.unescape(reading.strip())))
    rows.sort(key=lambda r: (r[0], r[1]))
    return rows


def reading_portrait_url(page_encoded: str, reading: str, ext: str = "jpg") -> str:
    """Construct the CDN portrait URL from observed roster fields (page name +
    reading). Per-officer namespaced, so it can only resolve to THIS officer's
    attachment — a wrong reading/ext 404s (honest FAIL), never a wrong face.
    Not fabrication: the reading is observed roster data and the ::attach/<reading>
    convention is confirmed against officer pages (曹操 -> ソウソウ.jpg)."""
    stem = urllib.parse.quote(reading, safe="")
    return f"https://cdn.wikiwiki.jp/to/w/sangokushi14/{page_encoded}/::attach/{stem}.{ext}"


def parse_portrait_url(html_text: str, page_encoded: str) -> str | None:
    """Discover the officer's portrait attachment URL from their page HTML.

    Matches image attachments in THIS officer's cdn namespace only (rejects
    embedded other-officer thumbnails). Prefers ::attach (faithful original) over
    ::ref (possible .webp derivative). Returns the query-stripped canonical URL,
    or None if the page carries no parseable portrait. Never fabricates a URL.

    ponytail: first-match-in-document-order = the top infobox portrait; the
    manual-QA identity check is the guard if a page ever front-loads another image.
    """
    pat = re.compile(
        r"https?://cdn\.wikiwiki\.jp/to/w/sangokushi14/"
        + re.escape(page_encoded)
        + r"/::(?:attach|ref)/[^\"'<> ]+?\.(?:jpg|jpeg|png|gif|webp)(?:\?[^\"'<> ]*)?",
        re.IGNORECASE,
    )
    urls = [html.unescape(m.group(0)) for m in pat.finditer(html_text)]
    if not urls:
        return None
    attach = [u for u in urls if "/::attach/" in u]
    chosen = attach[0] if attach else urls[0]
    return strip_query(chosen)


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
# rate limiting + fetch/cache                                                  #
# --------------------------------------------------------------------------- #
class RateLimiter:
    """Enforce a minimum delay between network requests (>= MIN_DELAY_SECONDS)."""

    def __init__(self, min_delay: float, clock=time.monotonic, sleeper=time.sleep):
        self.min_delay = max(MIN_DELAY_SECONDS, min_delay)
        self._clock = clock
        self._sleeper = sleeper
        self._last: float | None = None

    def wait(self) -> None:
        now = self._clock()
        if self._last is not None:
            elapsed = now - self._last
            if elapsed < self.min_delay:
                self._sleeper(self.min_delay - elapsed)
        self._last = self._clock()

    def sleep(self, seconds: float) -> None:
        """Explicit backoff sleep (resets the inter-request clock)."""
        if seconds > 0:
            self._sleeper(seconds)
        self._last = self._clock()


def _urllib_open(url: str, user_agent: str, timeout: float) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": user_agent})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        # 429 (rate limited) and 503 are transient — back off and retry politely.
        if e.code in (429, 503):
            try:
                retry_after = float(e.headers.get("Retry-After", "0") or 0)
            except (TypeError, ValueError):
                retry_after = 0.0
            raise _Transient(f"http_{e.code}", retry_after)
        if 400 <= e.code < 500:
            raise _PermanentHttp(e.code)
        raise _Transient(f"http_{e.code}")
    except (urllib.error.URLError, TimeoutError, OSError):
        raise _Transient("network_error")


class Fetcher:
    """Polite fetch with cache reuse. A cached canonical URL is never re-fetched
    and never waits on the rate limiter."""

    def __init__(
        self,
        cache_dir: Path,
        rate_limiter: RateLimiter,
        user_agent: str = DEFAULT_USER_AGENT,
        timeout: float = 20.0,
        retries: int = 2,
        opener=_urllib_open,
    ):
        self.cache_dir = cache_dir
        self.rate_limiter = rate_limiter
        self.user_agent = user_agent
        self.timeout = timeout
        self.retries = max(0, retries)
        self._opener = opener

    def _cache_path(self, canonical_url: str) -> Path:
        return self.cache_dir / (sha256_hex(canonical_url.encode("utf-8")) + ".bin")

    def fetch(self, canonical_url: str) -> tuple[bytes, bool]:
        """Return (bytes, cached). Raises FetchError on terminal failure.

        Transient failures (429/503/network) back off exponentially, honoring a
        server Retry-After when present, before the bounded retry."""
        cache_path = self._cache_path(canonical_url)
        if cache_path.exists():
            return cache_path.read_bytes(), True
        last_reason = "network_error"
        for attempt in range(self.retries + 1):
            self.rate_limiter.wait()
            try:
                data = self._opener(canonical_url, self.user_agent, self.timeout)
            except _PermanentHttp as e:
                raise FetchError(f"http_{e.code}")
            except _Transient as e:
                last_reason = e.reason
                if attempt < self.retries:
                    backoff = max(e.retry_after, self.rate_limiter.min_delay * (2 ** attempt))
                    self.rate_limiter.sleep(backoff)
                continue
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            cache_path.write_bytes(data)
            return data, False
        raise FetchError(last_reason)


# --------------------------------------------------------------------------- #
# image ops (live path uses OpenCV; tests inject a fake)                       #
# --------------------------------------------------------------------------- #
class CvImageOps:
    def __init__(self):
        import cv2  # noqa: PLC0415  (lazy: keep tests stdlib-only)

        self._cv2 = cv2
        self._np = __import__("numpy")

    def decode(self, data: bytes):
        arr = self._np.frombuffer(data, dtype=self._np.uint8)
        return self._cv2.imdecode(arr, self._cv2.IMREAD_COLOR)  # None on failure

    def size(self, img) -> tuple[int, int]:
        h, w = img.shape[:2]
        return int(w), int(h)

    def resize_encode(self, img, max_width: int, max_height: int):
        """Downscale the complete image to fit inside the bounds.

        Aspect ratio is preserved and small inputs are never enlarged. No crop,
        face detection, padding, or content-aware positioning is applied.
        """
        width, height = self.size(img)
        scale = min(1.0, max_width / width, max_height / height)
        out_width = max(1, min(max_width, int(round(width * scale))))
        out_height = max(1, min(max_height, int(round(height * scale))))
        resized = img
        if (out_width, out_height) != (width, height):
            resized = self._cv2.resize(
                img,
                (out_width, out_height),
                interpolation=self._cv2.INTER_AREA,
            )
        ok, buf = self._cv2.imencode(".png", resized)
        if not ok:
            raise FetchError("encode_failed")
        data = buf.tobytes()
        return data, out_width, out_height, "png"


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


def _resolve_image_url(target: Target, fetcher):
    """For a page target, fetch the officer page and discover the portrait URL.
    Returns (image_url, page_url). Raises FetchError on fetch failure or when the
    page has no parseable portrait (reason 'no_portrait_url')."""
    if target.kind != "page":
        return target.url, None
    page_data, _ = fetcher.fetch(strip_query(target.url))
    portrait = parse_portrait_url(page_data.decode("utf-8", "replace"), target.page_key)
    if not portrait:
        raise FetchError("no_portrait_url")
    return portrait, target.url


def process_target(
    target: Target,
    fetcher,
    image_ops,
    out_dir: Path,
    max_width: int = DEFAULT_MAX_WIDTH,
    max_height: int = DEFAULT_MAX_HEIGHT,
) -> ReportEntry:
    try:
        image_url, page_url = _resolve_image_url(target, fetcher)
    except FetchError as e:
        return _entry(target.name, None, "FAIL", e.reason, page_url=target.url)

    canonical = strip_query(image_url)
    base = {"page_url": page_url}
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
    (out_dir / out_name).write_bytes(out_data)

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
    entries.sort(key=lambda e: e["name"])
    counts: ReportCounts = {
        "OK": sum(e["status"] == "OK" for e in entries),
        "FAIL": sum(e["status"] == "FAIL" for e in entries),
    }
    return {
        "meta": {
            "tool": TOOL_NAME,
            "tool_version": TOOL_VERSION,
            "source": "rtk14/wikiwiki",
            "resize_bounds": {"max_width": max_width, "max_height": max_height},
            "total": len(entries),
            "counts": counts,
        },
        "entries": entries,
    }


def dump_report(report: Report) -> str:
    """Byte-stable serialization: sorted keys, pre-sorted entry list, LF end."""
    return json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


# --------------------------------------------------------------------------- #
# CLI                                                                          #
# --------------------------------------------------------------------------- #
def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="RTK14 local-only full-portrait resize pipeline")
    p.add_argument("--roster-url", default=DEFAULT_ROSTER_URL,
                   help="wiki roster page enumerated for officer names (default: 史実武将)")
    p.add_argument("--names", default=None,
                   help="manifest override: TSV of name<TAB>image_url (outside repo). "
                        "When given, skips wiki roster enumeration.")
    p.add_argument("--portrait-source", choices=("reading", "page"), default="reading",
                   help="wiki portrait discovery. 'reading' (default): construct the "
                        "CDN ::attach URL from the roster's reading column (fetches only "
                        "the un-throttled CDN). 'page': fetch each officer page and parse "
                        "its portrait attachment (faithful, but page host rate-limits).")
    p.add_argument("--source-dir", required=True,
                   help="raw/cache root (outside repo or gitignored)")
    p.add_argument("--out-dir", required=True,
                   help="full-image resized output dir (outside repo or gitignored)")
    p.add_argument("--report", required=True,
                   help="report JSON path (outside repo or gitignored)")
    p.add_argument("--limit", type=int, default=None, help="process at most N names")
    p.add_argument("--delay", type=float, default=3.0,
                   help=f"inter-request delay seconds (floored at {MIN_DELAY_SECONDS})")
    p.add_argument("--timeout", type=float, default=20.0)
    p.add_argument("--retries", type=int, default=2)
    p.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
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
    guarded = [source_dir, out_dir, report_path]
    if args.names:
        guarded.append(Path(args.names))
    for path in guarded:
        assert_safe_path(path, repo)

    fetcher = Fetcher(
        cache_dir=source_dir / "cache",
        rate_limiter=RateLimiter(args.delay),
        user_agent=args.user_agent,
        timeout=args.timeout,
        retries=args.retries,
    )

    if args.names:  # manifest mode (offline-deterministic source)
        rows = parse_manifest(Path(args.names).read_text(encoding="utf-8"))
        targets = [Target(name, url, "image", None) for name, url, _c in rows]
    else:  # wiki mode: enumerate officers from the roster page, then discover portraits
        roster_html, _ = fetcher.fetch(strip_query(args.roster_url))
        officers = parse_roster(roster_html.decode("utf-8", "replace"))
        if args.portrait_source == "page":
            targets = [
                Target(name, f"{WIKI_ORIGIN}/sangokushi14/{enc}", "page", enc)
                for name, enc, _reading in officers
            ]
        else:  # reading: construct the CDN portrait URL from the roster reading column
            targets = [
                Target(name, reading_portrait_url(enc, reading), "image", enc)
                for name, enc, reading in officers
                if reading
            ]

    if args.limit is not None:
        targets = targets[: max(0, args.limit)]

    image_ops = CvImageOps()
    report = build_report(
        targets,
        fetcher,
        image_ops,
        out_dir,
        args.max_width,
        args.max_height,
    )
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(dump_report(report), encoding="utf-8")

    c = report["meta"]["counts"]
    print(f"total={report['meta']['total']} OK={c['OK']} FAIL={c['FAIL']}")
    print(f"report={report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
