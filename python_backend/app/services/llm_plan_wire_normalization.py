"""Compatibility normalization for LlmPlan component wire values.

This layer repairs representation drift; it does not choose a generation route.
"""

import json
from typing import Any, List


_COMPONENT_TYPE_ALIASES = {
    "MAIN_MASS": "MASS_MAIN",
    "BUTTRESS": "WALL",
}


def _normalize_component_type(value: Any) -> str:
    if value is None:
        return ""
    upper = str(value).strip().upper()
    return _COMPONENT_TYPE_ALIASES.get(upper, upper)


def _normalize_features(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        out: List[str] = []
        for v in value:
            if v is None:
                continue
            if isinstance(v, dict):
                if "component_request" in v:
                    payload = v.get("component_request")
                    if payload is not None:
                        out.append("component_request:" + json.dumps(payload, ensure_ascii=False))
                        continue
                if "group_request" in v:
                    payload = v.get("group_request")
                    if payload is not None:
                        out.append("group_request:" + json.dumps(payload, ensure_ascii=False))
                        continue
                try:
                    s = json.dumps(v, ensure_ascii=False)
                except Exception:
                    s = str(v)
                if s.strip():
                    out.append(s)
                continue
            s = str(v)
            if s.strip():
                out.append(s)
        return out
    if isinstance(value, dict):
        return _normalize_features([value])
    if isinstance(value, str):
        return [value]
    return []


def _repair_component_request_strings(raw: str) -> str:
    if not raw:
        return raw
    if "component_request:" not in raw and "group_request:" not in raw:
        return raw
    prefixes = ("\"component_request:", "\"group_request:")
    i = 0
    n = len(raw)
    out: List[str] = []
    while i < n:
        matched = None
        for prefix in prefixes:
            if raw.startswith(prefix, i):
                matched = prefix
                break
        if matched is None:
            out.append(raw[i])
            i += 1
            continue
        key = "component_request" if matched.startswith("\"component_request") else "group_request"
        out.append("{\"")
        out.append(key)
        out.append("\":")
        i += len(matched)
        if i >= n or raw[i] != "{":
            if i < n:
                out.append(raw[i])
                i += 1
            continue
        start = i
        depth = 0
        in_str = False
        escape = False
        while i < n:
            ch = raw[i]
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == "\"":
                in_str = not in_str
            elif not in_str:
                if ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        i += 1
                        break
            i += 1
        out.append(raw[start:i])
        out.append("}")
        if i < n and raw[i] == "\"":
            i += 1
    return "".join(out)
