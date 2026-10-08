"""Bounded front-info failure metadata; loading this helper performs no operations."""
import json
import time
from urllib.error import HTTPError

FRONT_PATH = "/api/game/api/front-info?server=qa160"
MAX_BODY_BYTES = 4096
CONTENT_TYPES = frozenset({"application/json", "application/problem+json", "text/html", "text/plain"})
ERROR_CODES = frozenset({"AUTH_REQUIRED", "SERVER_NOT_PUBLIC", "SERVER_ADMISSION_UNAVAILABLE"})
ERROR_MESSAGES = frozenset({"게임 서버를 찾을 수 없습니다.", "서버 공개 상태를 확인할 수 없습니다."})


def _fields(error, actor_id, elapsed_ms):
    # A broken diagnostic must never replace the actual request failure.
    if type(actor_id) is not int or not 0 < actor_id < 2**31:
        raise ValueError("Diagnostic actor must be a positive game general ID")
    raw_type = error.headers.get("Content-Type", "") if error.headers else ""
    mime = raw_type.split(";", 1)[0].strip().lower() if len(raw_type) <= 128 else None
    mime = mime if mime in CONTENT_TYPES else None
    result = {
        "actorId": actor_id, "path": FRONT_PATH,
        "status": error.code if type(error.code) is int and 100 <= error.code <= 599 else None,
        "contentType": mime, "errorCode": None, "errorMessage": None,
        "elapsedMs": min(2**31 - 1, max(0, elapsed_ms)),
    }
    if mime not in {"application/json", "application/problem+json"}:
        return result
    try:
        body = error.read(MAX_BODY_BYTES + 1)
        if len(body) > MAX_BODY_BYTES:
            return result
        parsed = json.loads(body)
        entry = parsed.get("error") if isinstance(parsed, dict) else None
        code = entry.get("code") if isinstance(entry, dict) else None
        message = entry.get("message") if isinstance(entry, dict) else entry
        if isinstance(code, str) and code in ERROR_CODES:
            result["errorCode"] = code
        if isinstance(message, str) and message in ERROR_MESSAGES:
            result["errorMessage"] = message
    except Exception:
        # Do not expose body bytes, headers, credentials or parser exceptions.
        pass
    return result


def read_front(api, token, actor_id, record):
    """Call the existing API once; record allowlisted HTTP failures and re-raise."""
    started = time.monotonic()
    try:
        return api(FRONT_PATH, token=token)
    except HTTPError as error:
        try:
            elapsed_ms = int((time.monotonic() - started) * 1000)
            record(_fields(error, actor_id, elapsed_ms))
        except Exception:
            pass
        raise
