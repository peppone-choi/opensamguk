"""Make a bounded, reusable inventory from the cached wikiwiki sitemap/index pages.

No network requests are made here. The resulting inventory is private source data.
"""

import argparse
import hashlib
import json
import re
import shutil
from collections import Counter
from pathlib import Path
from urllib.parse import unquote, urlsplit

from bs4 import BeautifulSoup
from wikiwiki_fetch import cached_page_exists, compressed_for, read_cached_page


ROOT = Path.home() / ".cache/rtk14-wikiwiki"
KINDS = ("인물", "시나리오", "전법", "특성", "도시·지역", "관직", "세력", "이벤트", "기타")
INDEX_NAMES = ("FrontPage", "MenuBar", "史実武将", "史実武将(PK)", "メインシナリオ", "戦法", "個性", "都市", "爵位・官職", "イベント")
EXCLUDE = re.compile(r"^(?:コメント/|掲示板|.*掲示板.*|.*過去ログ.*|編集者|FrontPage$|MenuBar$)")
SCENARIO = re.compile(r"^\d{3}年\d{1,2}月[\s　]")


def key_from_url(url: str) -> str | None:
    parsed = urlsplit(url)
    if parsed.scheme not in ("", "https") or parsed.netloc not in ("", "wikiwiki.jp"):
        return None
    if parsed.query or parsed.fragment or "/::*" in parsed.path or "::" in parsed.path:
        return None
    prefix = "/sangokushi14/"
    if not parsed.path.startswith(prefix):
        return None
    return unquote(parsed.path[len(prefix):])


def linked_keys(root: Path, name: str, universe: set[str]) -> set[str]:
    path = root / "inventory-pages" / (hashlib.sha256(name.encode()).hexdigest()[:16] + ".html")
    soup = BeautifulSoup(read_cached_page(path), "html.parser")
    content = soup.find(id="content")
    if content is None:
        raise ValueError(f"missing content in {name}")
    return {key for a in content.find_all("a", href=True)
            if (key := key_from_url(a["href"])) in universe}


def inventory(root: Path) -> dict:
    urls = [line for line in (root / "sitemap.txt").read_text().splitlines() if line]
    by_key = {key_from_url(url): url for url in urls}
    if None in by_key or len(by_key) != len(urls):
        raise ValueError("sitemap includes invalid or duplicate page keys")
    universe = set(by_key)
    historical = (linked_keys(root, "史実武将", universe) |
                  linked_keys(root, "史実武将(PK)", universe)) - {"史実武将", "史実武将(PK)"}
    city = linked_keys(root, "都市", universe) - {"都市", "地域収入"}
    extra_people = (linked_keys(root, "個性", universe) | linked_keys(root, "戦法", universe)) - {
        "個性", "戦法", "都市", "イベント", "メインシナリオ", "史実武将", "史実武将(PK)"}
    people = (historical | extra_people) - city
    pages = []
    excluded = []
    for key, url in sorted(by_key.items()):
        reason = None
        if not key or key in ("FrontPage", "MenuBar"):
            reason = "목록·탐색 페이지"
        elif key.startswith("コメント/") or "掲示板" in key or "過去ログ" in key:
            reason = "댓글·게시판·잡담"
        elif key.startswith(("みんなの", "編集", "差分", "添付")) or key in {"テンプレート", "お役立ちリンク", "改善要望", "バグ・不具合", "裏ワザ・小ネタ", "序盤の進め方"}:
            reason = "편집·공략·외부 링크"
        if reason:
            excluded.append({"page_key": key, "url": url, "reason": reason})
            continue
        if key in people:
            kind = "인물"
        elif SCENARIO.match(key):
            kind = "시나리오"
        elif key == "戦法":
            kind = "전법"
        elif key == "個性":
            kind = "특성"
        elif key in city or key in {"都市", "地域収入"}:
            kind = "도시·지역"
        elif key in {"爵位・官職", "地域担当官"}:
            kind = "관직"
        elif key.startswith(("異民族", "勢力")) or key == "おすすめ君主":
            kind = "세력"
        elif key == "イベント" or "イベント" in key:
            kind = "이벤트"
        else:
            kind = "기타"
        pages.append({"page_key": key, "kind": kind, "url": url})
    counts = Counter(page["kind"] for page in pages)
    return {"source": "https://wikiwiki.jp/sangokushi14/sitemap.txt", "sitemap_count": len(urls),
            "total": len(pages), "counts": {kind: counts[kind] for kind in KINDS},
            "excluded_count": len(excluded), "estimated_seconds_at_2s": 2 * len(pages),
            "pages": pages, "excluded": excluded,
            "index_pages": list(INDEX_NAMES)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=ROOT)
    args = parser.parse_args()
    result = inventory(args.cache)
    path = args.cache / "inventory.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    # Index HTML was already collected during reconnaissance. Reuse its exact
    # bytes in the canonical page cache so the fetcher never requests it again.
    from wikiwiki_fetch import file_for
    for page in result["pages"]:
        if page["page_key"] not in INDEX_NAMES:
            continue
        source = args.cache / "inventory-pages" / (hashlib.sha256(page["page_key"].encode()).hexdigest()[:16] + ".html")
        target = file_for(args.cache, page)
        if cached_page_exists(source) and not cached_page_exists(target):
            target.parent.mkdir(parents=True, exist_ok=True)
            if compressed_for(source).is_file():
                shutil.copyfile(compressed_for(source), compressed_for(target))
            else:
                shutil.copyfile(source, target)
    print(json.dumps({key: result[key] for key in ("sitemap_count", "total", "counts", "excluded_count", "estimated_seconds_at_2s")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
