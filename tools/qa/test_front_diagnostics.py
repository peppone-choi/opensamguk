from contextlib import redirect_stderr, redirect_stdout
from email.message import Message
import io
import json
from pathlib import Path
import runpy
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError


class FrontDiagnosticsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.helper = runpy.run_path(str(Path(__file__).with_name("front-diagnostics.py")))
        cls.read_front = staticmethod(cls.helper["read_front"])

    def failure(self, body, status=503, content_type="application/json"):
        headers = Message()
        headers["Content-Type"] = content_type
        headers["Set-Cookie"] = "secret-cookie-must-not-be-recorded"
        error = HTTPError("http://private.invalid/secret", status, "secret-reason",
                          headers, io.BytesIO(body))
        self.addCleanup(error.close)
        return error

    def capture(self, error, actor=1387):
        api = Mock(side_effect=error)
        records = []
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            with patch.object(self.helper["time"], "monotonic", side_effect=[1.0, 1.125]):
                with self.assertRaises(type(error)) as raised:
                    self.read_front(api, "secret-token", actor, records.append)
        self.assertIs(raised.exception, error)
        api.assert_called_once_with("/api/game/api/front-info?server=qa160", token="secret-token")
        self.assertEqual(stdout.getvalue(), "")
        self.assertEqual(stderr.getvalue(), "")
        return records

    def test_success_returns_same_tuple_after_one_call_without_recording(self):
        result = (200, {"general": {"generalId": 1387}})
        api, record = Mock(return_value=result), Mock()
        self.assertIs(self.read_front(api, "secret-token", 1387, record), result)
        api.assert_called_once_with("/api/game/api/front-info?server=qa160", token="secret-token")
        record.assert_not_called()

    def test_admission_503_records_only_seven_allowlisted_fields(self):
        error = self.failure(json.dumps({"error": {
            "code": "SERVER_ADMISSION_UNAVAILABLE", "message": "서버 공개 상태를 확인할 수 없습니다.",
            "token": "secret-body-token"}, "account": "secret-account"}).encode())
        self.assertEqual(self.capture(error), [{
            "actorId": 1387, "path": "/api/game/api/front-info?server=qa160",
            "status": 503, "contentType": "application/json", "elapsedMs": 125,
            "errorCode": "SERVER_ADMISSION_UNAVAILABLE", "errorMessage": "서버 공개 상태를 확인할 수 없습니다.",
        }])

    def test_missing_selected_origin_matches_only_fixed_proxy_message(self):
        record = self.capture(self.failure(json.dumps({"error": "게임 서버를 찾을 수 없습니다."}).encode()))[0]
        self.assertIsNone(record["errorCode"])
        self.assertEqual(record["errorMessage"], "게임 서버를 찾을 수 없습니다.")

    def test_unknown_code_and_free_text_cannot_leak_body_or_headers(self):
        body = json.dumps({"error": {"code": "secret-code", "message": "secret-message",
                                   "Authorization": "secret-authorization"}, "username": "secret-account"}).encode()
        records = self.capture(self.failure(body))
        self.assertIsNone(records[0]["errorCode"])
        self.assertIsNone(records[0]["errorMessage"])
        self.assertNotIn("secret", json.dumps(records))

    def test_502_html_is_not_parsed_as_a_json_diagnostic(self):
        record = self.capture(self.failure(b"<html>secret-body</html>", 502, "text/html; charset=UTF-8"))[0]
        self.assertEqual(record["status"], 502)
        self.assertEqual(record["contentType"], "text/html")
        self.assertIsNone(record["errorCode"])
        self.assertIsNone(record["errorMessage"])

    def test_body_over_limit_is_discarded_after_one_bounded_read(self):
        body = json.dumps({"error": {"code": "SERVER_ADMISSION_UNAVAILABLE"}, "padding": "x" * 4096}).encode()
        error = self.failure(body)
        original_read = error.read
        error.read = Mock(side_effect=original_read)
        record = self.capture(error)[0]
        error.read.assert_called_once_with(4097)
        self.assertIsNone(record["errorCode"])
        self.assertIsNone(record["errorMessage"])

    def test_malformed_and_wrong_shape_json_leave_optional_fields_empty(self):
        for body in (b"{broken", b"[]", b"null", b'{"error":[]}', b'{"error":{"code":9}}', b"\xff"):
            with self.subTest(body=body):
                record = self.capture(self.failure(body))[0]
                self.assertIsNone(record["errorCode"])
                self.assertIsNone(record["errorMessage"])

    def test_code_allowlist_and_exact_message_matching(self):
        for code in ("AUTH_REQUIRED", "SERVER_NOT_PUBLIC", "SERVER_ADMISSION_UNAVAILABLE"):
            with self.subTest(code=code):
                record = self.capture(self.failure(json.dumps({"error": {
                    "code": code, "message": "서버 공개 상태를 확인할 수 없습니다.secret-suffix"}}).encode()))[0]
                self.assertEqual(record["errorCode"], code)
                self.assertIsNone(record["errorMessage"])

    def test_callback_failure_never_masks_the_original_http_error(self):
        error = self.failure(b"{}")
        with self.assertRaises(HTTPError) as raised:
            self.read_front(Mock(side_effect=error), "secret-token", 1387,
                            Mock(side_effect=RuntimeError("secret-callback")))
        self.assertIs(raised.exception, error)

    def test_failed_body_read_still_records_safe_metadata_and_original_error(self):
        error = self.failure(b"{}")
        error.read = Mock(side_effect=OSError("secret-body-read"))
        record = self.capture(error)[0]
        self.assertEqual(record["status"], 503)
        self.assertIsNone(record["errorCode"])
        self.assertNotIn("secret", json.dumps(record))

    def test_non_http_errors_are_not_caught_or_recorded(self):
        for error in (URLError("secret-network"), ValueError("secret-json")):
            with self.subTest(error_type=type(error).__name__):
                record = Mock()
                with self.assertRaises(type(error)) as raised:
                    self.read_front(Mock(side_effect=error), "secret-token", 1387, record)
                self.assertIs(raised.exception, error)
                record.assert_not_called()

    def test_invalid_actor_is_not_recorded_and_does_not_replace_http_error(self):
        for actor in (True, 0, -1, "secret-actor", 2**31):
            with self.subTest(actor_type=type(actor).__name__):
                self.assertEqual(self.capture(self.failure(b"{}"), actor), [])

    def test_untrusted_content_type_and_non_json_mime_never_expose_json_fields(self):
        body = b'{"error":{"code":"SERVER_ADMISSION_UNAVAILABLE"}}'
        for content_type in ("secret-type", "a" * 129, "text/plain"):
            with self.subTest(content_type=content_type[:10]):
                record = self.capture(self.failure(body, content_type=content_type))[0]
                self.assertEqual(record["contentType"], "text/plain" if content_type == "text/plain" else None)
                self.assertIsNone(record["errorCode"])

    def test_elapsed_time_and_status_have_bounded_numeric_fields(self):
        for end, expected in ((0.5, 0), (1e20, 2**31 - 1)):
            with self.subTest(end=end):
                error, records = self.failure(b"{}", status=999), []
                with patch.object(self.helper["time"], "monotonic", side_effect=[1.0, end]):
                    with self.assertRaises(HTTPError) as raised:
                        self.read_front(Mock(side_effect=error), "secret-token", 1387, records.append)
                self.assertIs(raised.exception, error)
                self.assertEqual(records[0]["elapsedMs"], expected)
                self.assertIsNone(records[0]["status"])


if __name__ == "__main__":
    unittest.main()
