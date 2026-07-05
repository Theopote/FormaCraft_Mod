"""
SSRF-safe HTTP fetch helpers for user-supplied reference URLs.

Used by vision_analyzer (web_url snippets) — never fetch arbitrary URLs without validation.
"""

from __future__ import annotations

import ipaddress
import logging
import os
import socket
from typing import Iterable, Optional, Sequence
from urllib.parse import urljoin, urlparse

import requests

logger = logging.getLogger(__name__)

_DEFAULT_ALLOWED_SCHEMES = frozenset({"http", "https"})
_BLOCKED_HOSTNAMES = frozenset({
    "localhost",
    "localhost.localdomain",
    "metadata.google.internal",
})


class UnsafeUrlError(ValueError):
    """Raised when a user-supplied URL fails SSRF validation."""


def _env_host_allowlist() -> Optional[frozenset[str]]:
    raw = (os.getenv("REFERENCE_URL_HOST_ALLOWLIST") or "").strip()
    if not raw:
        return None
    hosts = {h.strip().lower().rstrip(".") for h in raw.split(",") if h.strip()}
    return frozenset(hosts) if hosts else None


def _normalize_host(host: str) -> str:
    h = (host or "").strip().lower().rstrip(".")
    if h.startswith("[") and h.endswith("]"):
        h = h[1:-1]
    return h


def _is_ip_blocked(ip: ipaddress._BaseAddress) -> bool:
    if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_multicast:
        return True
    if ip.is_reserved or ip.is_unspecified:
        return True
    # AWS/GCP/Azure link-local metadata
    if isinstance(ip, ipaddress.IPv4Address):
        if ip in ipaddress.IPv4Network("169.254.169.254/32"):
            return True
        if ip in ipaddress.IPv4Network("100.64.0.0/10"):  # CGNAT
            return True
    return False


def _resolve_host_ips(host: str) -> Sequence[ipaddress._BaseAddress]:
    try:
        infos = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except socket.gaierror as e:
        raise UnsafeUrlError(f"cannot resolve host: {host}") from e
    ips: list[ipaddress._BaseAddress] = []
    seen: set[str] = set()
    for info in infos:
        sockaddr = info[4]
        if not sockaddr:
            continue
        ip_str = sockaddr[0]
        if ip_str in seen:
            continue
        seen.add(ip_str)
        try:
            ips.append(ipaddress.ip_address(ip_str))
        except ValueError:
            continue
    if not ips:
        raise UnsafeUrlError(f"no resolvable addresses for host: {host}")
    return ips


def validate_reference_url(url: str, *, allowed_schemes: Iterable[str] = _DEFAULT_ALLOWED_SCHEMES) -> str:
    """
    Validate user-supplied reference URL before server-side fetch.
    Returns normalized URL string; raises UnsafeUrlError on violation.
    """
    raw = (url or "").strip()
    if not raw:
        raise UnsafeUrlError("empty URL")

    parsed = urlparse(raw)
    scheme = (parsed.scheme or "").lower()
    allowed = {s.lower() for s in allowed_schemes}
    if scheme not in allowed:
        raise UnsafeUrlError(f"scheme not allowed: {scheme!r}")

    host = _normalize_host(parsed.hostname or "")
    if not host:
        raise UnsafeUrlError("missing hostname")

    if host in _BLOCKED_HOSTNAMES:
        raise UnsafeUrlError(f"blocked hostname: {host}")

    allowlist = _env_host_allowlist()
    if allowlist is not None and host not in allowlist:
        # also allow subdomains of allowlisted suffixes
        if not any(host == h or host.endswith("." + h) for h in allowlist):
            raise UnsafeUrlError(f"host not in allowlist: {host}")

    # literal IP in URL
    try:
        ip = ipaddress.ip_address(host)
        if _is_ip_blocked(ip):
            raise UnsafeUrlError(f"blocked IP: {host}")
        return raw
    except ValueError:
        pass

    for ip in _resolve_host_ips(host):
        if _is_ip_blocked(ip):
            raise UnsafeUrlError(f"host {host} resolves to blocked address: {ip}")

    if parsed.username or parsed.password:
        raise UnsafeUrlError("userinfo in URL is not allowed")

    return raw


def safe_http_get(
    url: str,
    *,
    timeout: float = 8.0,
    max_bytes: int = 8000,
    headers: Optional[dict[str, str]] = None,
    max_redirects: int = 3,
) -> requests.Response:
    """GET with SSRF validation on each redirect hop."""
    hdrs = headers or {"User-Agent": "FormaCraft/1.0 (architecture research)"}
    current = validate_reference_url(url)
    hop = 0

    while True:
        resp = requests.get(current, timeout=timeout, headers=hdrs, allow_redirects=False)
        if resp.status_code in (301, 302, 303, 307, 308):
            hop += 1
            if hop > max_redirects:
                resp.close()
                raise UnsafeUrlError(f"too many redirects (> {max_redirects})")
            location = resp.headers.get("Location") or resp.headers.get("location")
            resp.close()
            if not location:
                raise UnsafeUrlError("redirect without Location header")
            current = validate_reference_url(urljoin(current, location))
            continue

        resp.raise_for_status()
        if len(resp.content) > max_bytes:
            resp._content = resp.content[:max_bytes]
        return resp
