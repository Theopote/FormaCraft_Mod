"""
Offline tests for LlmPlan response_format selection and json_object fallback.

Run:
  py -3 python_backend/tools/llm_plan_response_format_smoke.py
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import List

_PY_BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(_PY_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_PY_BACKEND_ROOT))


class _FakeClient:
    def __init__(self, fail_schema: bool) -> None:
        self.fail_schema = fail_schema
        self.calls: List[str] = []

    @property
    def chat(self):
        return self

    @property
    def completions(self):
        return self

    def create(self, **kwargs):
        fmt = kwargs.get("response_format") or {}
        if fmt.get("type") == "json_schema":
            self.calls.append("json_schema")
            if self.fail_schema:
                raise RuntimeError("response_format json_schema is not supported")
        else:
            self.calls.append("json_object")

        class _Choice:
            message = type("Msg", (), {"content": '{"mode":"build","anchor":{"x":0,"y":64,"z":0}}'})()

        class _Resp:
            choices = [_Choice()]

        return _Resp()


def _assert(cond: bool, msg: str, errors: List[str]) -> None:
    if not cond:
        errors.append(msg)


def main() -> int:
    from app.models.llm_plan_json_schema import (
        call_chat_with_llm_plan_response_formats,
        iter_llm_plan_response_formats,
        should_fallback_to_json_object,
    )

    errors: List[str] = []

    os.environ["LLMPLAN_JSON_SCHEMA"] = "off"
    formats_off = iter_llm_plan_response_formats()
    _assert(len(formats_off) == 1 and formats_off[0][0] == "json_object", "off -> json_object only", errors)

    os.environ["LLMPLAN_JSON_SCHEMA"] = "auto"
    formats_auto = iter_llm_plan_response_formats()
    _assert(
        len(formats_auto) == 2
        and formats_auto[0][0] == "json_schema"
        and formats_auto[1][0] == "json_object",
        "auto -> json_schema then json_object",
        errors,
    )

    _assert(
        should_fallback_to_json_object(RuntimeError("response_format json_schema not supported")),
        "schema rejection should fallback",
        errors,
    )
    _assert(
        not should_fallback_to_json_object(TimeoutError("timed out")),
        "timeout should not fallback",
        errors,
    )

    client_ok = _FakeClient(fail_schema=False)
    _, label_ok = call_chat_with_llm_plan_response_formats(
        client_ok,
        model="test",
        messages=[{"role": "user", "content": "x"}],
        temperature=0,
        call_with_timeout=lambda fn, _timeout: fn(),
        timeout_sec=5,
    )
    _assert(label_ok == "json_schema" and client_ok.calls == ["json_schema"], "schema success", errors)

    client_fallback = _FakeClient(fail_schema=True)
    _, label_fb = call_chat_with_llm_plan_response_formats(
        client_fallback,
        model="test",
        messages=[{"role": "user", "content": "x"}],
        temperature=0,
        call_with_timeout=lambda fn, _timeout: fn(),
        timeout_sec=5,
    )
    _assert(
        label_fb == "json_object" and client_fallback.calls == ["json_schema", "json_object"],
        "schema failure falls back to json_object",
        errors,
    )

    if errors:
        print("FAIL")
        for err in errors:
            print(" - " + err)
        return 1

    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
