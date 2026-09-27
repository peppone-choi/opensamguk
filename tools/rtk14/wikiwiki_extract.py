"""Offline, loss-preserving extraction of cached RTK14 wikiwiki HTML.

Outputs stay in the private cache. This module never opens a network connection.
"""

import argparse
import gzip
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import urljoin

from bs4 import BeautifulSoup, Tag

from wikiwiki_fetch import CACHE, cached_page_exists, file_for, read_cached_page
from wikiwiki_inventory import key_from_url


SCENARIO_KEY = re.compile(r"^(\d{3})年(\d{1,2})月")
STAT_LABELS = {"統率": "leadership", "武力": "strength", "知力": "intelligence",
               "政治": "politics", "魅力": "charm", "生年": "birth", "没年": "death"}
DETAIL_LABELS = {"性別": "gender", "字": "courtesy_name", "登場": "appearanceYear",
                 "登場年": "appearanceYear", "相性": "affinity", "義理": "loyalty",
                 "出身": "origin", "出身地": "origin", "主義": "ideology", "政策": "policy"}
COMMENT_HEADINGS = {"コメント", "コメント欄"}


def compact(value: str) -> str:
    return " ".join(value.split())


def cell_data(cell: Tag, base_url: str, row: int, column: int) -> dict:
    readings = [compact(tag.get_text(" ", strip=True)) for tag in cell.find_all("rt")]
    links = []
    for anchor in cell.find_all("a", href=True):
        target = urljoin(base_url, anchor["href"])
        links.append({"text": compact(anchor.get_text(" ", strip=True)), "target": target})
    text = compact(cell.get_text(" ", strip=True))
    style = cell.get("style", "")
    color = re.search(r"(?:^|;)\s*background-color\s*:\s*(#[0-9a-fA-F]{3,8})", style)
    return {"text": text, "ruby_readings": readings, "links": links,
            "attributes": {key: value for key, value in cell.attrs.items() if key not in ("rowspan", "colspan")},
            "background_color": color[1] if color else cell.get("bgcolor"),
            "rowspan": max(1, int(cell.get("rowspan", 1))),
            "colspan": max(1, int(cell.get("colspan", 1))),
            "origin_row": row, "origin_column": column,
            "header": cell.name == "th" or cell.find("strong") is not None}


def expand_table(table: Tag, base_url: str) -> dict:
    grid: dict[tuple[int, int], dict] = {}
    headers: list[int] = []
    html_rows = [row for row in table.find_all("tr") if row.find_parent("table") is table]
    for row_index, row in enumerate(html_rows):
        column = 0
        cells = row.find_all(["td", "th"], recursive=False)
        if not cells:
            continue
        first_nonempty = next((cell for cell in cells if compact(cell.get_text(" ", strip=True))), None)
        section_header = (row_index > 0 and first_nonempty is not None
                          and first_nonempty.find("strong") is not None
                          and len(compact(first_nonempty.get_text(" ", strip=True))) <= 12
                          and int(first_nonempty.get("colspan", 1)) > 1)
        if (all(cell.name == "th" for cell in cells)
                or (row_index == 0 and all(cell.find("strong") is not None for cell in cells))
                or section_header):
            headers.append(row_index)
        for cell in cells:
            while (row_index, column) in grid:
                column += 1
            data = cell_data(cell, base_url, row_index, column)
            for r in range(row_index, row_index + data["rowspan"]):
                for c in range(column, column + data["colspan"]):
                    grid[r, c] = data
            column += data["colspan"]
    width = max((column for _, column in grid), default=-1) + 1
    rows = [[grid.get((row, column)) for column in range(width)] for row in range(len(html_rows))]
    return {"header_rows": headers, "rows": rows, "width": width}


def _table_blocks(table: dict) -> list[dict]:
    headers = table["header_rows"]
    if not headers:
        return []
    groups = []
    for index in headers:
        if not groups or index != groups[-1][-1] + 1:
            groups.append([index])
        else:
            groups[-1].append(index)
    blocks = []
    for group_index, header_block in enumerate(groups):
        labels = []
        for column in range(table["width"]):
            parts = []
            seen_origins = set()
            for index in header_block:
                cell = table["rows"][index][column]
                if cell is None:
                    continue
                origin = (cell["origin_row"], cell["origin_column"])
                if origin not in seen_origins and cell["text"]:
                    parts.append(cell["text"])
                    seen_origins.add(origin)
            labels.append(" / ".join(parts) or f"column_{column+1}")
        counts = Counter()
        for column, label in enumerate(labels):
            counts[label] += 1
            if counts[label] > 1:
                labels[column] = f"{label}#{counts[label]}"
        records = []
        row_indices = []
        start = header_block[-1] + 1
        end = groups[group_index + 1][0] if group_index + 1 < len(groups) else len(table["rows"])
        for index in range(start, end):
            row = table["rows"][index]
            if not any(cell and cell["text"] for cell in row):
                continue
            records.append({label: (row[column] or {}).get("text", "")
                            for column, label in enumerate(labels)})
            row_indices.append(index)
        blocks.append({"header_rows": header_block, "columns": labels,
                       "records": records, "row_indices": row_indices})
    return blocks


def normalize_page(page: dict, tables: list[dict], sections: list[dict]) -> tuple[dict, list[dict]]:
    normalized = {"page_key": page["page_key"], "url": page["url"], "kind": page["kind"],
                  "fields": {}, "table_data": [], "sections": sections}
    unmapped = []
    for table in tables:
        blocks = _table_blocks(table)
        normalized["table_data"].append({"table_index": table["table_index"],
                                         "heading": table["heading"], "blocks": blocks})
        if not blocks:
            unmapped.append({"page_key": page["page_key"], "table_index": table["table_index"],
                             "reason": "no header row; preserved in tables.jsonl"})
            continue
        for block in blocks:
            if len(block["records"]) == 1:
                for label, value in block["records"][0].items():
                    normalized["fields"].setdefault(label, []).append(value)
    if page["kind"] == "시나리오":
        match = SCENARIO_KEY.match(page["page_key"])
        if match:
            normalized["year_month"] = f"{int(match[1])}.{int(match[2])}"
            normalized["year"] = int(match[1])
            normalized["month"] = int(match[2])
            normalized["title"] = page["page_key"][match.end():].strip()
        forces = []
        for table, data in zip(tables, normalized["table_data"]):
            for block in data["blocks"]:
                if "勢力" not in block["columns"]:
                    continue
                color_column = block["columns"].index("色") if "色" in block["columns"] else None
                for row_index, record in zip(block["row_indices"], block["records"]):
                    row = table["rows"][row_index]
                    color_cell = row[color_column] if color_column is not None else None
                    forces.append({**record, "lord": record.get("君主") or record.get("勢力"),
                                   "color": color_cell.get("background_color") if color_cell else None})
        normalized["forces"] = forces
    if page["kind"] == "도시·지역":
        regions = []
        for table_data in normalized["table_data"]:
            for block in table_data["blocks"]:
                if not {"種類", "名称", "所属"}.issubset(block["columns"]):
                    continue
                for record in block["records"]:
                    if not record.get("名称") or not record.get("所属"):
                        continue
                    symbol = record.get("種類", "")
                    regions.append({"name": record["名称"], "reading": record.get("ヨミ"),
                                    "city": record["所属"], "site_type": (
                                        "city" if symbol == "⭖" else
                                        "port" if symbol == "⚓" else
                                        "gate" if "関" in record["名称"] and symbol else "region"),
                                    "symbol": symbol, "table_index": table_data["table_index"],
                                    "fields": record})
        normalized["regions"] = regions
        adjacency = (normalized["fields"].get("隣接都市") or [""])[0]
        normalized["adjacent_cities"] = [compact(name) for name in re.split(r"[、，,]", adjacency)
                                         if compact(name)]
        normalized["road_mentions"] = [section for section in sections
                                       if any(word in section["text"] for word in
                                              ("街道", "山道", "水路", "ルート", "進軍経路"))]
    if page["kind"] == "인물":
        mapped = {}
        for label, values in normalized["fields"].items():
            field = STAT_LABELS.get(label) or DETAIL_LABELS.get(label)
            if field:
                value = values[0]
                if field in STAT_LABELS.values() or field == "appearanceYear":
                    integer = re.match(r"\d+", value)
                    mapped[field] = int(integer[0]) if integer else None
                else:
                    mapped[field] = value
        normalized["attributes"] = mapped
        if tables and tables[0]["rows"]:
            top = tables[0]["rows"]
            visible_name = (top[0][0] or {}).get("text") or ""
            name_match = re.match(r"^(.*?)[（(]([^）)]+)[）)]", visible_name)
            normalized["name_kanji"] = name_match[1] if name_match else visible_name
            normalized["name_reading"] = name_match[2] if name_match else None
        for source_label, target in (("個性", "traits"), ("戦法", "tactics"), ("陣形", "formations")):
            normalized[target] = [value for label, values in normalized["fields"].items()
                                  if label == source_label or label.startswith(source_label + "#")
                                  for value in values if value and value != "-"]
        # A mixed row can start a new formation block while still listing extra tactics.
        for table in tables:
            for row_index in table["header_rows"]:
                row = table["rows"][row_index]
                if row and row[0] and row[0]["text"] == "陣形":
                    normalized["tactics"].extend(cell["text"] for cell in row
                                                  if cell and cell["origin_row"] == row_index
                                                  and cell["origin_column"] >= 5
                                                  and not cell["header"] and cell["text"])
        normalized["tactics"] = list(dict.fromkeys(normalized["tactics"]))
        relations = {}
        relation_labels = {"父親", "母親", "子供", "兄弟", "義兄弟", "血縁", "配偶者", "親愛", "嫌悪", "被親愛", "被嫌悪"}
        for table in tables:
            if not table["rows"]:
                continue
            labels = [(cell or {}).get("text") for cell in table["rows"][0]]
            if not relation_labels.intersection(labels):
                continue
            for row_index, row in enumerate(table["rows"][1:], start=1):
                for column, cell in enumerate(row):
                    if not cell or cell["origin_row"] != row_index or not cell["text"] or cell["text"] == "-":
                        continue
                    label = labels[column]
                    if label in relation_labels:
                        relations.setdefault(label, []).append(cell["text"])
        normalized["relationships"] = relations
        scenario_rows = []
        for table in tables:
            if not table["rows"]:
                continue
            labels = [(cell or {}).get("text") or f"column_{column+1}"
                      for column, cell in enumerate(table["rows"][0])]
            if "シナリオ" not in labels or "身分" not in labels:
                continue
            group = None
            for row in table["rows"][1:]:
                values = [(cell or {}).get("text", "") for cell in row]
                match = SCENARIO_KEY.match(values[0])
                if match:
                    scenario_rows.append({"year_month": f"{int(match[1])}.{int(match[2])}",
                                          "group": group, **dict(zip(labels, values))})
                elif values[0] and all(value == values[0] for value in values):
                    group = values[0]
        normalized["scenarios"] = scenario_rows
        biography = [section["text"] for section in sections
                     if any("列伝" in title for title in section["title_path"])]
        for table in tables:
            for row_index, row in enumerate(table["rows"][:-1]):
                if not any(cell and cell["origin_row"] == row_index and cell["text"] == "列伝" for cell in row):
                    continue
                following = table["rows"][row_index + 1]
                biography.extend(cell["text"] for column, cell in enumerate(following) if cell
                                 and cell["origin_row"] == row_index + 1
                                 and cell["origin_column"] == column and cell["text"])
        normalized["biography"] = list(dict.fromkeys(biography))
    return normalized, unmapped


def extract_page(page: dict, raw: bytes) -> tuple[list[dict], list[dict], dict, list[dict]]:
    soup = BeautifulSoup(raw, "html.parser")
    content = soup.find(id="content")
    if content is None:
        raise ValueError("missing #content")
    tables = []
    sections = []
    h2 = None
    h3 = None
    table_index = 0
    for tag in content.find_all(["h2", "h3", "table", "p", "dl", "ul", "ol"]):
        if tag.name == "h2":
            h2 = compact(tag.get_text(" ", strip=True))
            h3 = None
            if h2 in COMMENT_HEADINGS:
                break
            continue
        if tag.name == "h3":
            h3 = compact(tag.get_text(" ", strip=True))
            continue
        title_path = [title for title in (h2, h3) if title]
        if tag.name == "table":
            tables.append({"page_key": page["page_key"], "kind": page["kind"],
                           "table_index": table_index, "heading": title_path[-1] if title_path else None,
                           "title_path": title_path, **expand_table(tag, page["url"])})
            table_index += 1
            continue
        if tag.find_parent("table") or tag.find_parent(["ul", "ol", "dl"]):
            continue
        if tag.name in ("ul", "ol"):
            texts = [compact(li.get_text(" ", strip=True)) for li in tag.find_all("li", recursive=False)
                     if li.find("table") is None]
        elif tag.name == "dl":
            texts = [compact(item.get_text(" ", strip=True)) for item in tag.find_all(["dt", "dd"], recursive=False)]
        else:
            texts = [compact(tag.get_text(" ", strip=True))]
        for value in texts:
            if value:
                sections.append({"page_key": page["page_key"], "title_path": title_path,
                                 "tag": tag.name, "text": value})
    normalized, unmapped = normalize_page(page, tables, sections)
    return tables, sections, normalized, unmapped


def _write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


def legacy_officer_input(root: Path) -> tuple[list[dict], list[dict]]:
    """Build the existing refine_officers input from the historical roster only.

    The file is emitted only after all 1,000 detail pages parse successfully.
    """
    scenario_dir = Path(__file__).resolve().parents[1] / "scenario"
    sys.path.insert(0, str(scenario_dir))
    from parse_pages import parse_officer_page

    roster = root / "inventory-pages" / (hashlib.sha256("史実武将".encode()).hexdigest()[:16] + ".html")
    soup = BeautifulSoup(read_cached_page(roster), "html.parser")
    content = soup.find(id="content")
    if content is None:
        raise ValueError("historical roster has no #content")
    specs = {}
    for table in content.find_all("table"):
        rows = table.find_all("tr")
        if not rows:
            continue
        header = [compact(cell.get_text(" ", strip=True)) for cell in rows[0].find_all(["td", "th"], recursive=False)]
        if "名前" not in header or "読み" not in header:
            continue
        name_column, reading_column = header.index("名前"), header.index("読み")
        for row in rows[1:]:
            cells = row.find_all(["td", "th"], recursive=False)
            if len(cells) <= max(name_column, reading_column):
                continue
            anchor = cells[name_column].find("a", href=True)
            if anchor is None or (key := key_from_url(anchor["href"])) is None:
                continue
            specs[key] = {"name_kanji": compact(anchor.get_text(" ", strip=True)),
                          "name_reading": compact(cells[reading_column].get_text(" ", strip=True)),
                          "page_key": key}
    errors = []
    if len(specs) != 1000:
        errors.append({"page_key": "史実武将", "error": f"expected 1000 roster links, found {len(specs)}"})
    records = []
    for key, spec in sorted(specs.items()):
        page = {"page_key": key, "kind": "인물"}
        path = file_for(root, page)
        if not cached_page_exists(path):
            errors.append({"page_key": key, "error": "missing cached historical officer page"})
            continue
        try:
            records.append(parse_officer_page(read_cached_page(path).decode("utf-8", errors="replace"), **spec))
        except (OSError, ValueError) as error:
            errors.append({"page_key": key, "error": str(error)})
    return records, errors


def extract(root: Path) -> dict:
    inventory = json.loads((root / "inventory.json").read_text(encoding="utf-8"))
    destination = root / "extracted"
    destination.mkdir(parents=True, exist_ok=True)
    by_kind: dict[str, list] = defaultdict(list)
    unmapped = []
    failures = []
    missing = []
    count = Counter()
    table_count = Counter()
    field_names: dict[str, set] = defaultdict(set)
    with gzip.open(destination / "tables.jsonl.gz", "wt", encoding="utf-8", compresslevel=6) as table_output, \
         gzip.open(destination / "sections.jsonl.gz", "wt", encoding="utf-8", compresslevel=6) as section_output:
        for page in inventory["pages"]:
            path = file_for(root, page)
            if not cached_page_exists(path):
                missing.append(page["page_key"])
                continue
            try:
                page_tables, page_sections, normalized, page_unmapped = extract_page(page, read_cached_page(path))
            except (OSError, ValueError) as error:
                failures.append({"page_key": page["page_key"], "error": str(error)})
                continue
            kind = page["kind"]
            by_kind[kind].append(normalized)
            for row in page_tables:
                table_output.write(json.dumps(row, ensure_ascii=False) + "\n")
            for row in page_sections:
                section_output.write(json.dumps(row, ensure_ascii=False) + "\n")
            unmapped.extend(page_unmapped)
            count[kind] += 1
            table_count[kind] += len(page_tables)
            for table in normalized["table_data"]:
                for block in table["blocks"]:
                    field_names[kind].update(block["columns"])
    for kind in inventory["counts"]:
        _write_json(destination / f"{kind}.json", by_kind[kind])
    _write_json(destination / "unmapped.json", unmapped)
    coverage = {"complete": not missing and not failures, "inventory_total": inventory["total"],
                "cached_pages": sum(count.values()), "missing_count": len(missing),
                "missing_pages": missing, "parse_failures": failures,
                "by_kind": {kind: {"pages": count[kind], "tables": table_count[kind],
                                   "normalized_field_count": len(field_names[kind]),
                                   "fields": sorted(field_names[kind]),
                                   "unmapped_tables": sum(row["page_key"] in {p["page_key"] for p in by_kind[kind]}
                                                          for row in unmapped)} for kind in inventory["counts"]}}
    city_pages = by_kind["도시·지역"]
    city_index = next((page for page in city_pages if page["page_key"] == "都市"), None)
    expected_regions = {}
    if city_index:
        for table in city_index["table_data"]:
            for block in table["blocks"]:
                if not {"都市名", "地域数"}.issubset(block["columns"]):
                    continue
                for record in block["records"]:
                    if record["地域数"].isdigit():
                        expected_regions[record["都市名"]] = int(record["地域数"])
    actual_regions = {page["regions"][0]["city"]: len(page["regions"])
                      for page in city_pages
                      if page["page_key"] not in ("都市", "地域収入") and page.get("regions")}
    coverage["city_regions"] = {
        "index_city_count": len(expected_regions),
        "index_region_count": sum(expected_regions.values()),
        "detail_city_count": len(actual_regions),
        "detail_region_count": sum(actual_regions.values()),
        "missing_city_names": sorted(set(expected_regions) - set(actual_regions)),
        "region_count_mismatches": [
            {"city": city, "index": expected_regions[city], "detail": actual_regions[city]}
            for city in sorted(set(expected_regions) & set(actual_regions))
            if expected_regions[city] != actual_regions[city]
        ],
    }
    raw_records, raw_errors = legacy_officer_input(root)
    coverage["legacy_officer_input"] = {"parsed": len(raw_records), "errors": raw_errors,
                                         "complete": len(raw_records) == 1000 and not raw_errors}
    if coverage["legacy_officer_input"]["complete"]:
        _write_json(destination / "officer-data.json", raw_records)
    _write_json(destination / "coverage.json", coverage)
    return coverage


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=CACHE)
    args = parser.parse_args()
    coverage = extract(args.cache)
    print(json.dumps({key: coverage[key] for key in ("complete", "inventory_total", "cached_pages", "missing_count")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
