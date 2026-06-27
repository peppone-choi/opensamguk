#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import html
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path


BASE_URL = "http://www.myosam.com/dokuwiki/doku.php"
ROOT_ID = "help:start"
USER_AGENT = "Mozilla/5.0 opensamguk-wiki-ingest/1.0"


class DokuTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._skip_depth = 0
        self._href: str | None = None
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attrs_map = dict(attrs)
        if tag in {"script", "style", "noscript"}:
            self._skip_depth += 1
            return
        if self._skip_depth:
            return
        if tag in {"h1", "h2", "h3", "h4"}:
            level = {"h1": "#", "h2": "##", "h3": "###", "h4": "####"}[tag]
            self.parts.append(f"\n\n{level} ")
        elif tag in {"p", "div", "section", "article", "ul", "ol", "table", "tr"}:
            self.parts.append("\n")
        elif tag == "li":
            self.parts.append("\n- ")
        elif tag == "br":
            self.parts.append("\n")
        elif tag == "a":
            self._href = attrs_map.get("href")

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "noscript"} and self._skip_depth:
            self._skip_depth -= 1
            return
        if self._skip_depth:
            return
        if tag == "a":
            self._href = None
        elif tag in {"h1", "h2", "h3", "h4", "p", "li", "tr"}:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self._skip_depth:
            return
        text = " ".join(data.split())
        if not text:
            return
        if self._href and "doku.php?id=" in self._href:
            self.parts.append(f"[{text}]({self._href})")
        else:
            self.parts.append(text)
        self.parts.append(" ")

    def markdown(self) -> str:
        text = "".join(self.parts)
        text = html.unescape(text)
        text = re.sub(r"[ \t]+\n", "\n", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip() + "\n"


def page_url(page_id: str) -> str:
    return f"{BASE_URL}?id={urllib.parse.quote(page_id, safe=':')}"


def fetch(page_id: str, timeout: int) -> str:
    req = urllib.request.Request(page_url(page_id), headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as res:
        charset = res.headers.get_content_charset() or "utf-8"
        return res.read().decode(charset, errors="replace")


def help_links(doc: str) -> set[str]:
    found: set[str] = set()
    for raw in re.findall(r"doku\.php\?id=([^\"'&#<>]+)", doc):
        page_id = urllib.parse.unquote(raw)
        if page_id == ROOT_ID or page_id.startswith(ROOT_ID + ":"):
            found.add(page_id)
    return found


def safe_name(page_id: str) -> str:
    return page_id.replace(":", "__") + ".md"


def write_page(out_dir: Path, page_id: str, doc: str, fetched_at: str) -> None:
    parser = DokuTextParser()
    parser.feed(doc)
    markdown = parser.markdown()
    body = (
        "---\n"
        f"source: {page_url(page_id)}\n"
        f"dokuwiki_id: {page_id}\n"
        f"fetched: {fetched_at}\n"
        "ingested: false\n"
        "---\n\n"
        f"# {page_id}\n\n"
        f"{markdown}"
    )
    (out_dir / safe_name(page_id)).write_text(body, encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs/wiki/raw/myosam-help")
    ap.add_argument("--timeout", type=int, default=30)
    ap.add_argument("--delay", type=float, default=0.15)
    ap.add_argument("--max-pages", type=int, default=200)
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    fetched_at = dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).strftime("%Y-%m-%dT%H:%M:%S%z")

    queue = [ROOT_ID]
    seen: set[str] = set()
    failures: list[str] = []

    while queue and len(seen) < args.max_pages:
        page_id = queue.pop(0)
        if page_id in seen:
            continue
        try:
            doc = fetch(page_id, args.timeout)
        except (urllib.error.URLError, TimeoutError) as exc:
            failures.append(f"{page_id}\t{exc}")
            seen.add(page_id)
            continue
        seen.add(page_id)
        write_page(out_dir, page_id, doc, fetched_at)
        for link in sorted(help_links(doc)):
            if link not in seen and link not in queue:
                queue.append(link)
        time.sleep(args.delay)

    manifest = [
        "---",
        f"source: {page_url(ROOT_ID)}",
        f"fetched: {fetched_at}",
        f"pages: {len(seen) - len(failures)}",
        f"failures: {len(failures)}",
        "---",
        "",
        "# myosam help scrape manifest",
        "",
    ]
    for page_id in sorted(seen):
        if not any(f.startswith(page_id + "\t") for f in failures):
            manifest.append(f"- [{page_id}]({safe_name(page_id)})")
    if failures:
        manifest.extend(["", "## failures", ""])
        manifest.extend(f"- {line}" for line in failures)
    (out_dir / "MANIFEST.md").write_text("\n".join(manifest) + "\n", encoding="utf-8")

    print(f"fetched={len(seen) - len(failures)} failures={len(failures)} out={out_dir}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
