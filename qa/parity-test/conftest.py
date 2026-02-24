"""Shared fixtures for parity tests."""
import os
import pytest
import requests
import pymysql
import psycopg2

# ── Environment ──────────────────────────────────────────────────────────────
LEGACY_BASE = os.environ.get("LEGACY_BASE_URL", "http://legacy-app")
NEW_BASE = os.environ.get("NEW_BASE_URL", "http://new-gateway:8080")


# ── HTTP Sessions ────────────────────────────────────────────────────────────
class LegacyClient:
    """Wrapper around the legacy PHP API (api.php?path=…)."""

    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.session = requests.Session()

    def call(self, path: str, data: dict | None = None, method: str = "POST") -> requests.Response:
        url = f"{self.base}/api.php?path={path}"
        if method == "GET":
            return self.session.get(url, timeout=30)
        return self.session.post(url, json=data or {}, timeout=30)

    def get(self, path: str, params: dict | None = None) -> requests.Response:
        url = f"{self.base}/api.php?path={path}"
        return self.session.get(url, params=params, timeout=30)


class NewClient:
    """Wrapper around the new Kotlin/Spring API."""

    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.session = requests.Session()
        self.token: str | None = None

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    def post(self, path: str, data: dict | None = None) -> requests.Response:
        return self.session.post(
            f"{self.base}{path}", json=data or {}, headers=self._headers(), timeout=30
        )

    def get(self, path: str, params: dict | None = None) -> requests.Response:
        return self.session.get(
            f"{self.base}{path}", params=params, headers=self._headers(), timeout=30
        )

    def login(self, login_id: str, password: str):
        r = self.post("/api/auth/login", {"loginId": login_id, "password": password})
        r.raise_for_status()
        self.token = r.json().get("token") or r.json().get("accessToken")
        return r


@pytest.fixture(scope="session")
def legacy() -> LegacyClient:
    return LegacyClient(LEGACY_BASE)


@pytest.fixture(scope="session")
def new() -> NewClient:
    return NewClient(NEW_BASE)


# ── Database connections ─────────────────────────────────────────────────────
@pytest.fixture(scope="session")
def legacy_db():
    conn = pymysql.connect(
        host=os.environ.get("LEGACY_DB_HOST", "legacy-mariadb"),
        port=int(os.environ.get("LEGACY_DB_PORT", 3306)),
        user=os.environ.get("LEGACY_DB_USER", "root"),
        password=os.environ.get("LEGACY_DB_PASSWORD", "rootpw"),
        database=os.environ.get("LEGACY_DB_NAME", "sammo"),
        cursorclass=pymysql.cursors.DictCursor,
    )
    yield conn
    conn.close()


@pytest.fixture(scope="session")
def new_db():
    conn = psycopg2.connect(
        host=os.environ.get("NEW_DB_HOST", "new-postgres"),
        port=int(os.environ.get("NEW_DB_PORT", 5432)),
        user=os.environ.get("NEW_DB_USER", "opensam"),
        password=os.environ.get("NEW_DB_PASSWORD", "opensam123"),
        dbname=os.environ.get("NEW_DB_NAME", "opensam"),
    )
    conn.autocommit = True
    yield conn
    conn.close()
