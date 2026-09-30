#!/usr/bin/env python3
"""Validate catalog-backed help topics and explicitly registered extra topics."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXTRA_ID = re.compile(r"^(concepts|tutorial)\.[a-z][A-Za-z0-9]*$")
TOPIC_FIELDS = {"id", "title", "reviewState", "sections", "sources", "relatedTopicIds"}
SECTION_REQUIRED = {"explanation", "example", "successExample", "failureExample", "recoveryAdvice"}


def unique_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs)
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def validate(topics_doc: dict, registry_doc: dict, catalog_doc: dict) -> tuple[int, int]:
    if topics_doc.get("schemaVersion") != 1 or set(topics_doc) != {"schemaVersion", "topics"}:
        raise ValueError("invalid help topic file schema")
    if registry_doc.get("schemaVersion") != 1 or set(registry_doc) != {"schemaVersion", "topics"}:
        raise ValueError("invalid help topic registry schema")
    topics = topics_doc["topics"]
    registrations = registry_doc["topics"]
    inputs = catalog_doc["inputs"]
    if not all(isinstance(rows, list) for rows in (topics, registrations, inputs)):
        raise ValueError("topic, registry, and catalog rows must be lists")

    input_ids = {row["helpTopicId"] for row in inputs}
    if len(input_ids) != len(inputs):
        raise ValueError("catalog helpTopicId is not unique")
    registered: dict[str, str] = {}
    for row in registrations:
        if not isinstance(row, dict) or set(row) != {"id", "group"}:
            raise ValueError("invalid help topic registration")
        topic_id, group = row["id"], row["group"]
        if not isinstance(topic_id, str) or not EXTRA_ID.fullmatch(topic_id):
            raise ValueError(f"invalid extra help topic: {topic_id}")
        if group not in {"CONCEPT", "TUTORIAL"} or (group == "CONCEPT") != topic_id.startswith("concepts."):
            raise ValueError(f"help topic group does not match id: {topic_id}")
        if topic_id in registered or topic_id in input_ids:
            raise ValueError(f"duplicate or catalog-owned registration: {topic_id}")
        registered[topic_id] = group

    found: dict[str, dict] = {}
    for row in topics:
        if not isinstance(row, dict) or set(row) != TOPIC_FIELDS:
            raise ValueError("invalid help topic schema")
        topic_id = row["id"]
        if not isinstance(topic_id, str) or topic_id in found:
            raise ValueError(f"duplicate or invalid help topic: {topic_id}")
        for field in ("title", "reviewState"):
            if not isinstance(row[field], str) or not row[field].strip():
                raise ValueError(f"invalid help topic {field}: {topic_id}")
        if row["reviewState"] not in {"DRAFT", "APPROVED"}:
            raise ValueError(f"unknown help review state: {topic_id}")
        sections = row["sections"]
        if not isinstance(sections, dict) or not SECTION_REQUIRED <= sections.keys() or sections.keys() - SECTION_REQUIRED - {"historicalContext"}:
            raise ValueError(f"invalid help section schema: {topic_id}")
        if any(not isinstance(sections[key], str) or not sections[key].strip() for key in SECTION_REQUIRED):
            raise ValueError(f"blank help section: {topic_id}")
        if "historicalContext" in sections and sections["historicalContext"] is not None and not isinstance(sections["historicalContext"], str):
            raise ValueError(f"invalid historical context: {topic_id}")
        if not isinstance(row["sources"], list) or not isinstance(row["relatedTopicIds"], list):
            raise ValueError(f"invalid help links: {topic_id}")
        for source in row["sources"]:
            if not isinstance(source, dict) or not {"tradition", "work", "book"} <= source.keys() or source.keys() - {"tradition", "work", "book", "passage"}:
                raise ValueError(f"invalid help source schema: {topic_id}")
            if source["tradition"] not in {"CHRONICLE", "ROMANCE"}:
                raise ValueError(f"unknown help source tradition: {topic_id}")
            if any(not isinstance(source[key], str) or not source[key].strip() for key in ("tradition", "work", "book")):
                raise ValueError(f"blank help source: {topic_id}")
        found[topic_id] = row

    expected = input_ids | registered.keys()
    if found.keys() != expected:
        raise ValueError(f"help topics differ from catalog and registry: missing={sorted(expected - found.keys())}, orphan={sorted(found.keys() - expected)}")
    for topic_id, row in found.items():
        if any(not isinstance(link, str) or link not in found for link in row["relatedTopicIds"]):
            raise ValueError(f"unknown related topic: {topic_id}")
    return len(input_ids), len(registered)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--topics", type=Path, default=ROOT / "data/help/topics.json")
    parser.add_argument("--registry", type=Path, default=ROOT / "data/help/topic-registry.json")
    parser.add_argument("--catalog", type=Path, default=ROOT / "data/commands/input-catalog.json")
    args = parser.parse_args()
    try:
        input_count, extra_count = validate(load(args.topics), load(args.registry), load(args.catalog))
    except (KeyError, TypeError, ValueError) as exc:
        parser.exit(1, f"help topic registry invalid: {exc}\n")
    print(f"help topic registry valid: input={input_count} extra={extra_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
