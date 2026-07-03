"""
Probe whether the configured LLM endpoint supports OpenAI-style json_schema response_format.

Use this before switching generate_llm_plan() from json_object to json_schema.
Many local gateways (Ollama, older LM Studio) reject json_schema or ignore strict mode.

Run (from repo root, uses python_backend/.env if present):
  py -3 python_backend/tools/probe_json_schema_support.py
  py -3 python_backend/tools/probe_json_schema_support.py --model gpt-4o-mini
  py -3 python_backend/tools/probe_json_schema_support.py --provider ollama --base-url http://localhost:11434/v1 --model llama3.1

Exit code: 0 = json_schema strict works, 1 = unsupported or probe failed.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

_PY_BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(_PY_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_PY_BACKEND_ROOT))

try:
    from dotenv import load_dotenv

    load_dotenv(_PY_BACKEND_ROOT / ".env")
except ImportError:
    pass


# Minimal LlmPlan slice — shared with generate_llm_plan() via app.models.llm_plan_json_schema.
from app.models.llm_plan_json_schema import (  # noqa: E402
    JSON_OBJECT_RESPONSE_FORMAT,
    build_llm_plan_response_format,
)

_PROBE_MESSAGES = [
    {
        "role": "user",
        "content": (
            'Return JSON only: {"mode":"build","anchor":{"x":0,"y":64,"z":0}}. '
            "No markdown, no commentary."
        ),
    }
]


def _probe(
    client: Any,
    model: str,
    response_format: Dict[str, Any],
    label: str,
) -> Tuple[bool, str]:
    try:
        response = client.chat.completions.create(
            model=model,
            messages=_PROBE_MESSAGES,
            response_format=response_format,
            temperature=0,
            max_tokens=128,
        )
    except Exception as exc:
        return False, f"{label}: API error — {type(exc).__name__}: {exc}"

    raw = (response.choices[0].message.content or "").strip()
    if not raw:
        return False, f"{label}: empty response body"

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        return False, f"{label}: response is not JSON — {exc.msg}; body={raw[:200]!r}"

    if not isinstance(parsed, dict):
        return False, f"{label}: root must be object, got {type(parsed).__name__}"
    if parsed.get("mode") not in ("build", "patch"):
        return False, f"{label}: missing/invalid mode in {parsed!r}"
    anchor = parsed.get("anchor")
    if not isinstance(anchor, dict):
        return False, f"{label}: missing anchor object in {parsed!r}"
    for key in ("x", "y", "z"):
        if key not in anchor:
            return False, f"{label}: anchor.{key} missing in {parsed!r}"

    return True, f"{label}: OK — {raw[:120]}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe json_schema support for the configured LLM.")
    parser.add_argument("--provider", default=None, help="Override LLM_PROVIDER")
    parser.add_argument("--base-url", default=None, help="Override LLM_BASE_URL / OPENAI_BASE_URL")
    parser.add_argument("--api-key", default=None, help="Override OPENAI_API_KEY")
    parser.add_argument("--model", default=None, help="Override model name")
    parser.add_argument("--skip-json-object", action="store_true", help="Skip baseline json_object probe")
    args = parser.parse_args()

    from app.services.llm_client import build_config, get_client, get_client_from_fields

    class _Req:
        def __init__(self) -> None:
            self.llmProvider = args.provider
            self.llmBaseUrl = args.base_url
            self.apiKey = args.api_key
            self.model = args.model

    req = _Req()
    cfg = build_config(req)
    model = args.model or cfg.model

    client = get_client(req)
    if client is None and args.api_key:
        client = get_client_from_fields(args.api_key, args.provider, args.base_url)
    if client is None:
        print("FAIL: no LLM client (set OPENAI_API_KEY or use --api-key; Ollama may use key 'ollama')")
        return 1

    print(f"provider={cfg.provider} base_url={cfg.base_url or '(sdk default)'} model={model}")
    print()

    if not args.skip_json_object:
        ok, msg = _probe(client, model, JSON_OBJECT_RESPONSE_FORMAT, "json_object")
        print(msg)
        if not ok:
            print("\nBaseline json_object failed — fix connectivity before testing json_schema.")
            return 1
        print()

    strict_format = build_llm_plan_response_format(strict=True)
    ok_strict, msg_strict = _probe(client, model, strict_format, "json_schema (strict=True)")
    print(msg_strict)

    loose_format = build_llm_plan_response_format(strict=False)
    ok_loose, msg_loose = _probe(client, model, loose_format, "json_schema (strict=False)")
    print(msg_loose)
    print()

    if ok_strict:
        print("VERDICT: json_schema strict is SUPPORTED — safe to pilot on generate_llm_plan().")
        return 0
    if ok_loose:
        print(
            "VERDICT: json_schema works but strict=True failed — "
            "keep json_object or use non-strict schema until provider fixes strict mode."
        )
        return 1
    print(
        "VERDICT: json_schema NOT SUPPORTED — keep response_format={\"type\": \"json_object\"} "
        "and rely on Pydantic validation."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
