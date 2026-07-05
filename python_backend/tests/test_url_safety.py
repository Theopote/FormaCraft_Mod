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


if __name__ == "__main__":
    unittest.main()
