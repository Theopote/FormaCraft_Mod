"""SSRF validation for user-supplied reference URLs."""

from __future__ import annotations

import unittest
from unittest.mock import patch

from app.services.url_safety import UnsafeUrlError, validate_reference_url


class UrlSafetyTest(unittest.TestCase):
    def test_allows_public_https(self):
        url = validate_reference_url("https://example.com/page")
        self.assertTrue(url.startswith("https://"))

    def test_rejects_file_scheme(self):
        with self.assertRaises(UnsafeUrlError):
            validate_reference_url("file:///etc/passwd")

    def test_rejects_localhost(self):
        with self.assertRaises(UnsafeUrlError):
            validate_reference_url("http://127.0.0.1:8000/admin")

    def test_allows_loopback_when_explicitly_opted_in(self):
        url = validate_reference_url("http://127.0.0.1:11434/v1/models", allow_loopback=True)
        self.assertIn("127.0.0.1", url)
        url2 = validate_reference_url("http://localhost:11434/v1/models", allow_loopback=True)
        self.assertIn("localhost", url2)

    def test_loopback_opt_in_still_blocks_metadata(self):
        with self.assertRaises(UnsafeUrlError):
            validate_reference_url("http://169.254.169.254/latest/meta-data/", allow_loopback=True)

    def test_loopback_opt_in_still_blocks_metadata_hostname(self):
        # allow_loopback must not exempt cloud metadata hostnames.
        with self.assertRaises(UnsafeUrlError):
            validate_reference_url("http://metadata.google.internal/computeMetadata/v1/", allow_loopback=True)

    def test_rejects_metadata_ip(self):
        with self.assertRaises(UnsafeUrlError):
            validate_reference_url("http://169.254.169.254/latest/meta-data/")

    @patch("app.services.url_safety._resolve_host_ips")
    def test_rejects_private_dns_resolution(self, mock_resolve):
        mock_resolve.return_value = [__import__("ipaddress").ip_address("10.0.0.1")]
        with self.assertRaises(UnsafeUrlError):
            validate_reference_url("http://evil.example.com/")

    def test_variation_birds_nest_not_rejected_by_localhost_string_in_path(self):
        # path containing 127.0.0.1 as text is ok if host is public — host is example.com
        url = validate_reference_url("https://example.com/docs/127.0.0.1-notes")
        self.assertIn("example.com", url)

    @patch("app.services.url_safety.requests.get")
    def test_safe_http_get_streams_and_caps_body(self, mock_get):
        from app.services.url_safety import safe_http_get

        class _FakeResp:
            status_code = 200
            headers = {}
            _content = b""

            @property
            def content(self):
                return self._content

            def raise_for_status(self):
                return None

            def iter_content(self, chunk_size=4096):
                yield b"a" * 100
                yield b"b" * 100

            def close(self):
                return None

        mock_get.return_value = _FakeResp()
        resp = safe_http_get("https://example.com/page", max_bytes=50)
        self.assertEqual(len(resp.content), 50)
        mock_get.assert_called()
        self.assertTrue(mock_get.call_args.kwargs.get("stream"))


if __name__ == "__main__":
    unittest.main()
