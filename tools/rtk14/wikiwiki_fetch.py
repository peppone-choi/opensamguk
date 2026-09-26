"""Rate-limited, resumable collection of an approved wikiwiki inventory."""

import argparse
import datetime as dt
import email.utils
import errno
import fcntl
import gzip
import hashlib
import json
import os
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
from pathlib import Path


CACHE = Path.home() / ".cache/rtk14-wikiwiki"
USER_AGENT = "OpenSamguk-RTK14-Research/1.0 (single-request public data research; contact: github.com/peppone-choi/opensamguk)"
MIN_DELAY = 2.0
MIN_FREE_BYTES = 500 * 1024 * 1024


def file_for(root: Path, page: dict) -> Path:
    key = page["page_key"]
    if not isinstance(key, str) or key in ("", ".", "..") or "\\" in key:
        raise ValueError("invalid page key")
    return root / "pages" / page["kind"] / (urllib.parse.quote(key, safe="") + ".html")


def compressed_for(path: Path) -> Path:
    return path.with_name(path.name + ".gz")


def cached_page_exists(path: Path) -> bool:
    return path.is_file() or compressed_for(path).is_file()


def read_cached_page(path: Path) -> bytes:
    compressed = compressed_for(path)
    return gzip.decompress(compressed.read_bytes()) if compressed.is_file() else path.read_bytes()


def robots(root: Path) -> urllib.robotparser.RobotFileParser:
    path = root / "robots.txt"
    if not path.is_file():
        raise FileNotFoundError("read and cache current robots.txt before collection")
    parser = urllib.robotparser.RobotFileParser()
    parser.parse(path.read_text().splitlines())
    return parser


def allowed(parser: urllib.robotparser.RobotFileParser, url: str) -> bool:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.netloc != "wikiwiki.jp" or not parsed.path.startswith("/sangokushi14/"):
        return False
    if parsed.query or parsed.fragment or "::" in urllib.parse.unquote(parsed.path):
        return False
    return parser.can_fetch(USER_AGENT, url)


class CheckedRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, parser: urllib.robotparser.RobotFileParser):
        self.parser = parser

    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if not allowed(self.parser, newurl):
            raise ValueError(f"redirect target is disallowed: {newurl}")
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def retry_after_seconds(value: str | None) -> float:
    if value is None:
        return 0.0
    try:
        return max(0.0, float(value))
    except ValueError:
        try:
            target = email.utils.parsedate_to_datetime(value)
            return max(0.0, (target - dt.datetime.now(dt.timezone.utc)).total_seconds())
        except (TypeError, ValueError, OverflowError):
            return 0.0


def append_log(path: Path, entry: dict) -> None:
    encoded = (json.dumps(entry, ensure_ascii=False) + "\n").encode("utf-8")
    while True:
        original_size = path.stat().st_size if path.exists() else 0
        try:
            with path.open("ab") as output:
                output.write(encoded)
                output.flush()
                os.fsync(output.fileno())
            return
        except OSError as exc:
            if exc.errno != errno.ENOSPC:
                raise
            if path.exists():
                with path.open("r+b") as output:
                    output.truncate(original_size)
            print("disk full while writing fetch log; waiting 60s without another request", flush=True)
            time.sleep(60)


def wait_for_disk_space(path: Path) -> None:
    warned = False
    while shutil.disk_usage(path).free < MIN_FREE_BYTES:
        if not warned:
            print("disk free below 500 MiB; waiting without another request", flush=True)
            warned = True
        time.sleep(60)
    if warned:
        print("disk free recovered; resuming collection", flush=True)


def save_page(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    compressed = compressed_for(path)
    partial = compressed.with_name(compressed.name + ".partial")
    encoded = gzip.compress(data, compresslevel=6, mtime=0)
    while True:
        wait_for_disk_space(path.parent)
        try:
            with partial.open("wb") as output:
                output.write(encoded)
                output.flush()
                os.fsync(output.fileno())
            os.replace(partial, compressed)
            return
        except OSError as exc:
            if exc.errno != errno.ENOSPC:
                raise
            partial.unlink(missing_ok=True)
            print("disk full while saving page; waiting 60s with response in memory", flush=True)
            time.sleep(60)


def collect(root: Path, *, limit: int | None = None, minimum_delay: float = MIN_DELAY,
            page_keys: set[str] | None = None) -> dict:
    inventory = json.loads((root / "inventory.json").read_text())
    if inventory["total"] > 5000:
        raise ValueError("inventory exceeds 5,000 pages")
    parser = robots(root)
    if minimum_delay < MIN_DELAY:
        raise ValueError("minimum delay must be at least two seconds")
    delay = max(minimum_delay, parser.crawl_delay(USER_AGENT) or 0)
    opener = urllib.request.build_opener(CheckedRedirect(parser))
    log_path = root / "fetch-log.jsonl"
    result = {"cached": 0, "fetched": 0, "failed": 0, "rate_limited": 0,
              "stopped": None, "delay": delay}
    last_request = 0.0
    if log_path.is_file():
        lines = log_path.read_text(encoding="utf-8").splitlines()
        if lines:
            latest = json.loads(lines[-1])
            timestamp = latest.get("started_at") or latest.get("fetched_at")
            if timestamp:
                elapsed = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(timestamp)).total_seconds()
                last_request = time.monotonic() - max(0.0, elapsed)
    consecutive_http_errors = 0
    consecutive_rate_limits = 0
    # A second process may not run requests against the same cache.
    with (root / "fetch.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for page in inventory["pages"]:
            if page_keys is not None and page["page_key"] not in page_keys:
                continue
            path = file_for(root, page)
            if cached_page_exists(path):
                result["cached"] += 1
                continue
            url = page["url"]
            if not allowed(parser, url):
                result["stopped"] = f"robots/disallowed: {url}"
                break
            attempt = 0
            request_number = 0
            while attempt < 3:
                wait_for_disk_space(root)
                time.sleep(max(0.0, delay - (time.monotonic() - last_request)))
                last_request = time.monotonic()
                started_at = dt.datetime.now(dt.timezone.utc).isoformat()
                request_number += 1
                data = None
                status = None
                error = None
                retry_after = None
                try:
                    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
                    with opener.open(request, timeout=40) as response:
                        status = response.status
                        if not allowed(parser, response.url):
                            raise ValueError(f"redirected to disallowed URL: {response.url}")
                        data = response.read()
                except urllib.error.HTTPError as exc:
                    status = exc.code
                    error = f"http_{status}"
                    retry_after = exc.headers.get("Retry-After") if exc.headers else None
                    exc.close()
                except (OSError, ValueError) as exc:
                    error = type(exc).__name__
                    if "disallowed" in str(exc):
                        result["stopped"] = str(exc)
                stamp = dt.datetime.now(dt.timezone.utc).isoformat()
                digest = hashlib.sha256(data).hexdigest() if data is not None else None
                append_log(log_path, {"started_at": started_at, "fetched_at": stamp, "url": url, "status": status,
                                      "sha256": digest, "kind": page["kind"], "attempt": request_number,
                                      "error": error})
                if result["stopped"]:
                    break
                if status == 429:
                    consecutive_rate_limits += 1
                    result["rate_limited"] += 1
                    wait = max(60.0 * 2 ** min(consecutive_rate_limits - 1, 4), retry_after_seconds(retry_after))
                    delay = min(max(delay * 2, 10.0), 120.0)
                    result["delay"] = delay
                    print(f"HTTP 429; waiting {wait:.0f}s before retrying {url}", flush=True)
                    time.sleep(wait)
                    continue
                if status is not None and 400 <= status <= 599:
                    consecutive_http_errors += 1
                    if consecutive_http_errors >= 5:
                        result["stopped"] = f"five consecutive HTTP errors ending at {url}"
                        break
                elif status is not None and 200 <= status < 400:
                    consecutive_http_errors = 0
                    consecutive_rate_limits = 0
                if data is not None and status == 200:
                    save_page(path, data)
                    result["fetched"] += 1
                    break
                attempt += 1
                if attempt == 3:
                    result["failed"] += 1
            if result["stopped"]:
                break
            if limit is not None and result["fetched"] >= limit:
                break
            if result["fetched"] and result["fetched"] % 100 == 0:
                print(json.dumps(result, ensure_ascii=False), flush=True)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=CACHE)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--minimum-delay", type=float, default=MIN_DELAY)
    parser.add_argument("--page-key", action="append", dest="page_keys")
    args = parser.parse_args()
    result = collect(args.cache, limit=args.limit, minimum_delay=args.minimum_delay,
                     page_keys=set(args.page_keys) if args.page_keys else None)
    print(json.dumps(result, ensure_ascii=False), flush=True)
    if result["stopped"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
