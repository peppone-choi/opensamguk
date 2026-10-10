"""Advisory automation only. No dispatch, registry writes, or remote mutations."""
import hashlib
from pathlib import Path


def tool_version():
    value = hashlib.sha256()
    for path in sorted(Path(__file__).parent.glob("*.py")):
        value.update(path.name.encode() + b"\0" + path.read_bytes())
    return "sha256:" + value.hexdigest()
