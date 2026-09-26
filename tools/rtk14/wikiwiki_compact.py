"""Losslessly compact cached HTML, then remove only verified originals."""

import argparse
import fcntl
import gzip
import hashlib
import os
from pathlib import Path

from wikiwiki_fetch import CACHE, compressed_for


def compact(root: Path) -> dict:
    result = {"converted": 0, "original_bytes": 0, "compressed_bytes": 0}
    with (root / "fetch.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for directory in (root / "pages", root / "inventory-pages"):
            if not directory.is_dir():
                continue
            for source in sorted(directory.rglob("*.html")):
                original = source.read_bytes()
                digest = hashlib.sha256(original).digest()
                target = compressed_for(source)
                if target.is_file():
                    restored = gzip.decompress(target.read_bytes())
                    if hashlib.sha256(restored).digest() != digest or restored != original:
                        raise ValueError(f"compressed cache differs from source: {source}")
                else:
                    partial = target.with_name(target.name + ".partial")
                    with partial.open("wb") as output:
                        output.write(gzip.compress(original, compresslevel=6, mtime=0))
                        output.flush()
                        os.fsync(output.fileno())
                    if gzip.decompress(partial.read_bytes()) != original:
                        raise ValueError(f"compressed cache verification failed: {source}")
                    os.replace(partial, target)
                result["original_bytes"] += len(original)
                result["compressed_bytes"] += target.stat().st_size
                source.unlink()
                result["converted"] += 1
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=CACHE)
    args = parser.parse_args()
    print(compact(args.cache))


if __name__ == "__main__":
    main()
