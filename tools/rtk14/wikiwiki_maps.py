"""Cache only selected full-size map attachments linked by already cached pages."""

import argparse
import datetime as dt
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
from pathlib import Path

from bs4 import BeautifulSoup

from wikiwiki_fetch import CACHE, USER_AGENT, file_for, read_cached_page, retry_after_seconds


CDN = "cdn.wikiwiki.jp"


def map_links(raw: bytes) -> list[dict]:
    content = BeautifulSoup(raw, "html.parser").find(id="content")
    if content is None:
        raise ValueError("missing #content")
    heading = ""
    result = []
    for tag in content.find_all(["h2", "h3", "h4", "a"]):
        if tag.name != "a":
            heading = tag.get_text(" ", strip=True)
            continue
        if "地図" not in heading or tag.get("data-lightbox") is None:
            continue
        url = tag.get("href", "")
        parsed = urllib.parse.urlsplit(url)
        if (parsed.scheme == "https" and parsed.netloc == CDN
                and parsed.path.startswith("/to/w/sangokushi14/")
                and "/::attach/" in parsed.path):
            result.append({"heading": heading, "url": url, "filename": tag.get("title")})
    return result


def cdn_rules(root: Path) -> urllib.robotparser.RobotFileParser:
    status = json.loads((root / "cdn-robots-status.json").read_text())
    if status.get("url") != f"https://{CDN}/robots.txt":
        raise ValueError("CDN robots status must be checked before collection")
    parser = urllib.robotparser.RobotFileParser()
    parser.set_url(status["url"])
    if status.get("status") == 404:
        parser.allow_all = True
    elif status.get("status") == 200:
        parser.parse((root / "cdn-robots.txt").read_text().splitlines())
    else:
        raise ValueError("CDN robots status does not permit collection")
    return parser


def collect_maps(root: Path, page_keys: list[str], delay: float = 20.0) -> dict:
    if delay < 2:
        raise ValueError("minimum delay is two seconds")
    inventory = json.loads((root / "inventory.json").read_text())
    pages = {page["page_key"]: page for page in inventory["pages"]}
    rules = cdn_rules(root)
    chosen = []
    for key in page_keys:
        page = pages[key]
        source = file_for(root, page)
        for index, link in enumerate(map_links(read_cached_page(source))):
            if not rules.can_fetch(USER_AGENT, link["url"]):
                raise ValueError(f"image blocked by robots: {link['url']}")
            chosen.append((page, index, link))
    log_path = root / "image-fetch-log.jsonl"
    last_request = 0.0
    last_stamps = []
    for previous_log in (root / "fetch-log.jsonl", log_path):
        if previous_log.is_file():
            lines = previous_log.read_text(encoding="utf-8").splitlines()
            if lines:
                latest = json.loads(lines[-1])
                stamp = latest.get("started_at") or latest.get("fetched_at")
                if stamp:
                    last_stamps.append(dt.datetime.fromisoformat(stamp))
    if last_stamps:
        elapsed = (dt.datetime.now(dt.timezone.utc) - max(last_stamps)).total_seconds()
        last_request = time.monotonic() - max(0.0, elapsed)
    result = {"cached": 0, "fetched": 0, "failed": 0}
    for page, index, link in chosen:
        extension = Path(urllib.parse.unquote(urllib.parse.urlsplit(link["url"]).path)).suffix.lower()
        if extension not in (".png", ".jpg", ".jpeg"):
            raise ValueError("unsupported map image type")
        path = root / "images" / "maps" / f"{urllib.parse.quote(page['page_key'], safe='')}-{index}{extension}"
        if path.is_file():
            result["cached"] += 1
            continue
        attempt = 0
        rate_limits = 0
        while attempt < 3:
            time.sleep(max(0, delay - (time.monotonic() - last_request)))
            last_request = time.monotonic()
            data = None
            status = None
            content_type = None
            try:
                request = urllib.request.Request(link["url"], headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(request, timeout=60) as response:
                    status = response.status
                    content_type = response.headers.get_content_type()
                    if response.url != link["url"]:
                        raise ValueError("unexpected map redirect")
                    data = response.read(25_000_001)
            except urllib.error.HTTPError as exc:
                status = exc.code
                retry_after = exc.headers.get("Retry-After") if exc.headers else None
                exc.close()
                if status == 429:
                    rate_limits += 1
                    time.sleep(max(60 * 2 ** min(rate_limits - 1, 4),
                                   retry_after_seconds(retry_after)))
            if data is not None and (len(data) > 25_000_000 or content_type not in ("image/jpeg", "image/png")):
                raise ValueError("unexpected map image body")
            digest = hashlib.sha256(data).hexdigest() if data is not None else None
            with log_path.open("a", encoding="utf-8") as log:
                log.write(json.dumps({"fetched_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                                      "source_page": page["url"], "heading": link["heading"],
                                      "url": link["url"], "status": status, "sha256": digest,
                                      "content_type": content_type, "path": str(path), "attempt": attempt + 1},
                                     ensure_ascii=False) + "\n")
            if status == 429:
                continue
            if data is not None:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                result["fetched"] += 1
                break
            attempt += 1
            if attempt == 3:
                result["failed"] += 1
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=CACHE)
    parser.add_argument("--page-key", action="append", required=True)
    parser.add_argument("--minimum-delay", type=float, default=20.0)
    args = parser.parse_args()
    print(json.dumps(collect_maps(args.cache, args.page_key, args.minimum_delay), ensure_ascii=False))


if __name__ == "__main__":
    main()
