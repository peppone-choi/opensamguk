import argparse
import html
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ANCHOR = re.compile(r"<a\b(?P<attributes>[^>]*)>(?P<body>.*?)</a>", re.IGNORECASE | re.DOTALL)
ATTRIBUTE = re.compile(r"(?P<name>[\w:-]+)\s*=\s*(?P<quote>['\"])(?P<value>.*?)(?P=quote)", re.DOTALL)
TABLE = re.compile(r"<table\b[^>]*>(?P<body>.*?)</table>", re.IGNORECASE | re.DOTALL)
ROW = re.compile(r"<tr\b[^>]*>(?P<body>.*?)</tr>", re.IGNORECASE | re.DOTALL)
CELL = re.compile(r"<t[dh]\b(?P<attributes>[^>]*)>(?P<body>.*?)</t[dh]>", re.IGNORECASE | re.DOTALL)
COLSPAN = re.compile(r"\bcolspan\s*=\s*(?:['\"])?(?P<span>\d+)", re.IGNORECASE)
TAG = re.compile(r"<[^>]+>")
RUBY_READING = re.compile(r"<rt\b[^>]*>(?P<body>.*?)</rt>", re.IGNORECASE | re.DOTALL)
RUBY_PARENTHESIS = re.compile(r"<rp\b[^>]*>.*?</rp>", re.IGNORECASE | re.DOTALL)
PARENTHETICAL_READING = re.compile(r"[（(][^）)]*[）)]")
YEAR_MONTH = re.compile(r"^\s*(?P<year>\d+)年\s*(?P<month>\d+)月")
LEADING_INTEGER = re.compile(r"^\s*(?P<value>\d+)")
FINGERPRINT_LABELS = (
    ("birth", "生年"),
    ("death", "没年"),
    ("leadership", "統率"),
    ("strength", "武力"),
    ("intelligence", "知力"),
    ("politics", "政治"),
    ("charm", "魅力"),
)


class PageCollectionError(ValueError):
    pass


def _attributes(value: str) -> dict[str, str]:
    return {match.group("name").lower(): html.unescape(match.group("value")) for match in ATTRIBUTE.finditer(value)}


def _text(value: str) -> str:
    return " ".join(html.unescape(TAG.sub("", value)).split())


def _normal_text(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    return None if normalized in ("", "-") else normalized


def _integer(value: str | None) -> int | None:
    if value is None:
        return None
    match = LEADING_INTEGER.match(value)
    return int(match.group("value")) if match else None


def _page_key(href: str) -> str | None:
    parsed = urllib.parse.urlparse(href)
    path = urllib.parse.unquote(parsed.path)
    key = path.rsplit("/", 1)[-1].strip()
    if not key or key in (".", "..") or "/" in key or "\\" in key:
        return None
    return key


def _anchor_name(body: str, attributes: dict[str, str]) -> tuple[str | None, str | None]:
    reading_match = RUBY_READING.search(body)
    reading = _normal_text(_text(reading_match.group("body"))) if reading_match else None
    without_reading = RUBY_READING.sub("", RUBY_PARENTHESIS.sub("", body))
    visible = PARENTHETICAL_READING.sub("", _text(without_reading)).strip()
    name = _normal_text(visible) or _normal_text(attributes.get("title"))
    if reading is None:
        parenthetical = re.search(r"[（(]([^）)]+)[）)]", _text(body))
        reading = _normal_text(parenthetical.group(1)) if parenthetical else _normal_text(attributes.get("data-reading"))
    return name, reading


def parse_roster(html_text: str) -> list[dict]:
    roster: list[dict] = []
    known_keys: dict[str, str] = {}
    for anchor in ANCHOR.finditer(html_text):
        attributes = _attributes(anchor.group("attributes"))
        href = attributes.get("href")
        if not href:
            continue
        key = _page_key(href)
        if key is None:
            continue
        name, reading = _anchor_name(anchor.group("body"), attributes)
        if name is None:
            continue
        previous_name = known_keys.get(key)
        if previous_name is not None:
            if previous_name != name:
                raise ValueError(f"conflicting visible names for page key {key}: {previous_name}, {name}")
            continue
        known_keys[key] = name
        roster.append({"name_kanji": name, "name_reading": reading, "page_key": key})
    if not roster:
        raise ValueError("roster contains no officer links")
    return roster


def _cells(row_html: str) -> list[dict]:
    cells: list[dict] = []
    for match in CELL.finditer(row_html):
        attributes = match.group("attributes")
        span_match = COLSPAN.search(attributes)
        cells.append({
            "text": _text(match.group("body")),
            "span": int(span_match.group("span")) if span_match else 1,
            "header": "<strong" in match.group("body").lower(),
        })
    return cells


def _is_header(cells: list[dict]) -> bool:
    for cell in cells:
        if cell["text"]:
            return bool(cell["header"])
    return False


def _columns(cells: list[dict]) -> list[tuple[int, int]]:
    offset = 0
    ranges: list[tuple[int, int]] = []
    for cell in cells:
        end = offset + cell["span"]
        ranges.append((offset, end))
        offset = end
    return ranges


def _pair(header: list[dict], values: list[dict]) -> dict[str, str]:
    output: dict[str, str] = {}
    value_columns = _columns(values)
    for cell, (start, end) in zip(header, _columns(header)):
        if not cell["header"] or not cell["text"]:
            continue
        matching = [
            value["text"]
            for value, (value_start, _) in zip(values, value_columns)
            if start <= value_start < end and value["text"]
        ]
        if matching:
            output[cell["text"]] = "|".join(matching)
    return output


def _table_values(html_text: str) -> tuple[dict[str, str], list[dict[str, str]]]:
    values: dict[str, str] = {}
    scenario_rows: list[dict[str, str]] = []
    for table in TABLE.finditer(html_text):
        rows = [_cells(match.group("body")) for match in ROW.finditer(table.group("body"))]
        for index, header in enumerate(rows):
            if not _is_header(header):
                continue
            following: list[list[dict]] = []
            next_index = index + 1
            while next_index < len(rows) and not _is_header(rows[next_index]):
                if any(cell["text"] for cell in rows[next_index]):
                    following.append(rows[next_index])
                next_index += 1
            if not following:
                continue
            mapped = [_pair(header, row) for row in following]
            if len(mapped) == 1:
                for label, value in mapped[0].items():
                    values.setdefault(label, value)
            else:
                scenario_rows.extend(mapped)
    return values, scenario_rows


def _year_month(value: str | None) -> str | None:
    if value is None:
        return None
    match = YEAR_MONTH.match(value)
    return f"{int(match.group('year'))}.{int(match.group('month'))}" if match else None


def _scenario_rows(rows: list[dict[str, str]]) -> list[dict]:
    scenarios: list[dict] = []
    for row in rows:
        if "シナリオ" in row:
            label = row.get("シナリオ")
            status = row.get("身分")
            location = row.get("所在")
            faction = row.get("勢力")
            office = row.get("官職")
        elif len(row) == 1:
            parts = next(iter(row.values())).split("|")
            if len(parts) < 5:
                continue
            label, status, location, faction = parts[0], parts[2], parts[3], parts[4]
            office = parts[6] if len(parts) > 6 else None
        else:
            continue
        year_month = _year_month(label)
        if year_month is None:
            continue
        scenarios.append({
            "year_month": year_month,
            "status": _normal_text(status),
            "location": _normal_text(location),
            "faction": _normal_text(faction),
            "office": _normal_text(office),
        })
    return scenarios


def parse_officer_page(html: str, *, name_kanji: str, name_reading: str, page_key: str) -> dict:
    values, rows = _table_values(html)
    fingerprint = {field: _integer(values.get(label)) for field, label in FINGERPRINT_LABELS}
    missing_fields = [field for field, value in fingerprint.items() if value is None]
    if missing_fields:
        raise ValueError(f"missing required fingerprint fields: {', '.join(missing_fields)}")
    return {
        "name_kanji": name_kanji,
        "name_reading": _normal_text(name_reading),
        "page_key": page_key,
        **fingerprint,
        "scenarios": _scenario_rows(rows),
    }


def _read_page(specification: dict, page_cache: Path) -> dict:
    page_key = specification["page_key"]
    page_path = page_cache / f"{page_key}.html"
    if not page_path.is_file():
        raise FileNotFoundError(f"missing cached page: {page_path}")
    return parse_officer_page(
        page_path.read_text(encoding="utf-8", errors="replace"),
        name_kanji=specification["name_kanji"],
        name_reading=specification.get("name_reading") or "",
        page_key=page_key,
    )


def _collect(specifications: list[dict], page_cache: Path) -> list[dict]:
    records: list[dict] = []
    errors: list[str] = []
    for specification in specifications:
        try:
            records.append(_read_page(specification, page_cache))
        except (OSError, ValueError) as error:
            errors.append(f"{specification.get('page_key', '?')}: {error}")
    if errors:
        raise PageCollectionError("; ".join(errors))
    return records


def collect_pages(roster_html: Path, page_cache: Path) -> list[dict]:
    return _collect(parse_roster(roster_html.read_text(encoding="utf-8", errors="replace")), page_cache)


def collect_pagecrawl(pagecrawl: Path, page_cache: Path) -> list[dict]:
    payload = json.loads(pagecrawl.read_text(encoding="utf-8"))
    rows = payload.get("rows")
    if not isinstance(rows, list):
        raise ValueError("pagecrawl rows must be a list")
    specifications: list[dict] = []
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("pagecrawl row must be an object")
        encoded = row.get("page_encoded")
        name = row.get("name")
        if not isinstance(encoded, str) or not isinstance(name, str):
            raise ValueError("pagecrawl row requires name and page_encoded")
        key = urllib.parse.unquote(encoded)
        if not key or "/" in key or "\\" in key:
            raise ValueError(f"invalid page key: {encoded}")
        specifications.append({"name_kanji": name, "name_reading": row.get("reading"), "page_key": key})
    return _collect(sorted(specifications, key=lambda item: (item["name_kanji"], item["page_key"])), page_cache)


def _atomic_write(destination: Path, content: bytes) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent)
    try:
        with os.fdopen(descriptor, "wb") as temporary:
            temporary.write(content)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, destination)
    except BaseException:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
        raise


def fetch_to_cache(url: str, destination: Path, *, user_agent: str, minimum_delay: float, timeout: float, retries: int) -> None:
    if not user_agent.strip():
        raise ValueError("network fetch requires a User-Agent")
    if minimum_delay < 1.0:
        raise ValueError("network fetch requires a minimum delay of at least 1.0 second")
    if timeout <= 0 or retries < 1:
        raise ValueError("timeout must be positive and retries must be at least one")
    if destination.exists():
        return
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": user_agent})
            with urllib.request.urlopen(request, timeout=timeout) as response:
                _atomic_write(destination, response.read())
            return
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
            if attempt + 1 < retries:
                time.sleep(minimum_delay * (attempt + 1))
    raise RuntimeError(f"failed to fetch {url}: {last_error}")


def _write_json(path: Path, value: object) -> None:
    _atomic_write(path, (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"))


def _parse_arguments(arguments: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--roster", type=Path)
    source.add_argument("--pagecrawl", type=Path)
    parser.add_argument("--page-cache", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--fetch-base-url")
    parser.add_argument("--user-agent")
    parser.add_argument("--minimum-delay", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--retries", type=int, default=3)
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    options = _parse_arguments(arguments if arguments is not None else sys.argv[1:])
    try:
        if options.fetch_base_url:
            if not options.user_agent:
                raise ValueError("--fetch-base-url requires --user-agent")
            if options.roster is None:
                raise ValueError("--fetch-base-url requires --roster")
            specifications = parse_roster(options.roster.read_text(encoding="utf-8", errors="replace"))
            for specification in specifications:
                page_url = f"{options.fetch_base_url.rstrip('/')}/{urllib.parse.quote(specification['page_key'])}"
                fetch_to_cache(
                    page_url,
                    options.page_cache / f"{specification['page_key']}.html",
                    user_agent=options.user_agent,
                    minimum_delay=options.minimum_delay,
                    timeout=options.timeout,
                    retries=options.retries,
                )
                time.sleep(options.minimum_delay)
        records = collect_pages(options.roster, options.page_cache) if options.roster else collect_pagecrawl(options.pagecrawl, options.page_cache)
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        _write_json(options.report, {"records": 0, "errors": [str(error)]})
        print(str(error), file=sys.stderr)
        return 1
    _write_json(options.out, records)
    _write_json(options.report, {"records": len(records), "errors": []})
    print(f"parsed {len(records)} officer pages")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
