"""Read the reviewed pre-Geuk map after later releases changed han-tiles.json.

The gzip payload is the exact 8ae8ffcc map restored through the frontier and
Geuk ledgers. Its uncompressed bytes match the frozen relocation input SHA-256.
"""

import gzip
import hashlib
import json
from pathlib import Path

from tools.map import relocate_han_province as relocation

FIXTURE = Path(__file__).parent / "fixtures" / "han-tiles-geuk-input-v1.json.gz"


def input_bytes() -> bytes:
    source = gzip.decompress(FIXTURE.read_bytes())
    if hashlib.sha256(source).hexdigest() != relocation.INPUT_SHA256:
        raise ValueError("frozen Geuk input fixture has drifted")
    return source


def relocated_document() -> dict:
    ledger = json.loads(relocation.LEDGER.read_text(encoding="utf-8"))
    return relocation.relocate_document(json.loads(input_bytes()), ledger)
