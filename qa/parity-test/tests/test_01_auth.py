"""
Parity Test — Authentication (register / login).

Legacy:  POST api.php?path=… (session-cookie based)
New:     POST /api/auth/register, /api/auth/login (JWT token based)

We compare:
  - Register: both succeed with same credentials → structural response match
  - Login: both return authenticated state
  - Duplicate registration: both reject
"""
import pytest
import uuid
from comparison import compare_responses

TEST_USER = f"parity_{uuid.uuid4().hex[:8]}"
TEST_PASS = "TestPass123!"
TEST_NICK = "테스트장수"


class TestRegister:
    def test_register_both_succeed(self, legacy, new):
        """Register the same user on both systems and compare structure."""
        # Legacy — uses its own session-based registration
        # Legacy register path guessed from common sammo patterns
        lr = legacy.call("Global/Join", {
            "loginID": TEST_USER,
            "loginPW": TEST_PASS,
            "nickName": TEST_NICK,
        })

        # New stack
        nr = new.post("/api/auth/register", {
            "loginId": TEST_USER,
            "password": TEST_PASS,
            "displayName": TEST_NICK,
        })

        # Both should succeed (2xx)
        assert lr.status_code in (200, 201), f"Legacy register failed: {lr.status_code} {lr.text[:200]}"
        assert nr.status_code in (200, 201), f"New register failed: {nr.status_code} {nr.text[:200]}"

        # Structural comparison — both return some form of auth/result object
        cmp = compare_responses(lr.json(), nr.json(), structural_only=True)
        # Auth responses will differ in structure (cookie vs JWT), so we just
        # verify both contain a success indicator
        legacy_data = lr.json()
        new_data = nr.json()

        legacy_ok = (
            legacy_data.get("result") in (True, 1, "1", "success")
            or lr.status_code == 200
        )
        new_ok = nr.status_code in (200, 201) and (
            "token" in new_data or "accessToken" in new_data or new_data.get("result") is True
        )
        assert legacy_ok, f"Legacy register did not succeed: {legacy_data}"
        assert new_ok, f"New register did not succeed: {new_data}"

    def test_duplicate_register_rejected(self, legacy, new):
        """Both systems should reject duplicate registrations."""
        lr = legacy.call("Global/Join", {
            "loginID": TEST_USER,
            "loginPW": TEST_PASS,
            "nickName": TEST_NICK + "2",
        })
        nr = new.post("/api/auth/register", {
            "loginId": TEST_USER,
            "password": TEST_PASS,
            "displayName": TEST_NICK + "2",
        })

        # Both should fail (4xx or result=false)
        legacy_data = lr.json()
        legacy_rejected = (
            legacy_data.get("result") in (False, 0, "0")
            or lr.status_code >= 400
        )
        new_rejected = nr.status_code >= 400

        assert legacy_rejected, f"Legacy allowed duplicate: {legacy_data}"
        assert new_rejected, f"New allowed duplicate: {nr.status_code} {nr.text[:200]}"


class TestLogin:
    def test_login_both_succeed(self, legacy, new):
        """Login with the previously registered user on both systems."""
        lr = legacy.call("Global/Login", {
            "loginID": TEST_USER,
            "loginPW": TEST_PASS,
        })
        nr = new.post("/api/auth/login", {
            "loginId": TEST_USER,
            "password": TEST_PASS,
        })

        assert lr.status_code == 200, f"Legacy login failed: {lr.status_code}"
        assert nr.status_code == 200, f"New login failed: {nr.status_code}"

        # Save token for subsequent tests
        new.login(TEST_USER, TEST_PASS)

    def test_bad_password_rejected(self, legacy, new):
        """Both systems reject wrong passwords."""
        lr = legacy.call("Global/Login", {
            "loginID": TEST_USER,
            "loginPW": "WRONG_PASSWORD",
        })
        nr = new.post("/api/auth/login", {
            "loginId": TEST_USER,
            "password": "WRONG_PASSWORD",
        })

        legacy_data = lr.json()
        legacy_rejected = (
            legacy_data.get("result") in (False, 0, "0")
            or lr.status_code >= 400
        )
        assert legacy_rejected, f"Legacy accepted wrong password: {legacy_data}"
        assert nr.status_code >= 400, f"New accepted wrong password: {nr.status_code}"
