#!/usr/bin/env python3
"""
Parse [LlmPlanMetrics] lines from Minecraft / Formacraft server logs.

Usage:
  python scripts/analyze_llmplan_metrics.py run/logs/latest.log
  python scripts/analyze_llmplan_metrics.py run/logs/*.log
  Get-Content run/logs/latest.log | python scripts/analyze_llmplan_metrics.py -

Example log line:
  [LlmPlanMetrics] event=fallback detail=ROUTING_POLICY player=Steve prompt="..." tagged=3 ...
"""

from __future__ import annotations

import argparse
import glob
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

METRICS_MARKER = "[LlmPlanMetrics]"
EVENT_RE = re.compile(r"\bevent=(\w+)")
DETAIL_RE = re.compile(r"\bdetail=(\S+)")
SNAPSHOT_RE = re.compile(
    r"tagged=(\d+) success=(\d+) errors=(\d+) fallback=(\d+) "
    r"fallback_rate=([\d.]+)% success_rate=([\d.]+)% "
    r"direct_structure=(\d+) structure_after_fallback=(\d+)"
)

ACTIONABLE_FALLBACK_DETAILS = frozenset({"MISSING_LLM_PLAN_JSON", "EMPTY_OUTPUT"})


@dataclass
class Snapshot:
    tagged: int = 0
    success: int = 0
    errors: int = 0
    fallback: int = 0
    fallback_rate_percent: float = 0.0
    success_rate_percent: float = 0.0
    direct_structure: int = 0
    structure_after_fallback: int = 0


def parse_snapshot(line: str) -> Snapshot | None:
    match = SNAPSHOT_RE.search(line)
    if not match:
        return None
    return Snapshot(
        tagged=int(match.group(1)),
        success=int(match.group(2)),
        errors=int(match.group(3)),
        fallback=int(match.group(4)),
        fallback_rate_percent=float(match.group(5)),
        success_rate_percent=float(match.group(6)),
        direct_structure=int(match.group(7)),
        structure_after_fallback=int(match.group(8)),
    )


def iter_metric_lines(paths: list[Path]) -> list[str]:
    lines: list[str] = []
    for path in paths:
        if path == Path("-"):
            lines.extend(sys.stdin)
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        lines.extend(text.splitlines())
    return [line for line in lines if METRICS_MARKER in line]


def resolve_inputs(raw: list[str]) -> list[Path]:
    if not raw:
        return []
    resolved: list[Path] = []
    for item in raw:
        if item == "-":
            resolved.append(Path("-"))
            continue
        matches = glob.glob(item)
        if matches:
            resolved.extend(Path(p) for p in sorted(matches))
        else:
            resolved.append(Path(item))
    return resolved


def pct(part: int, total: int) -> str:
    if total <= 0:
        return "0.0%"
    return f"{100.0 * part / total:.1f}%"


def print_bar(label: str, count: int, total: int, width: int = 28) -> None:
    if total <= 0:
        fill = 0
    else:
        fill = max(0, min(width, round(width * count / total)))
    bar = "#" * fill + "-" * (width - fill)
    print(f"  {label:<28} {count:>5}  {pct(count, total):>6}  |{bar}|")


def analyze(lines: list[str]) -> int:
    if not lines:
        print("No [LlmPlanMetrics] lines found.", file=sys.stderr)
        return 1

    event_counts: Counter[str] = Counter()
    fallback_by_detail: Counter[str] = Counter()
    last_snapshot: Snapshot | None = None

    for line in lines:
        event_match = EVENT_RE.search(line)
        if not event_match:
            continue
        event = event_match.group(1)
        event_counts[event] += 1

        if event == "fallback":
            detail_match = DETAIL_RE.search(line)
            detail = detail_match.group(1) if detail_match else "(missing)"
            fallback_by_detail[detail] += 1

        snapshot = parse_snapshot(line)
        if snapshot is not None:
            last_snapshot = snapshot

    total_fallback_events = sum(fallback_by_detail.values())
    actionable = sum(
        fallback_by_detail[d] for d in ACTIONABLE_FALLBACK_DETAILS if d in fallback_by_detail
    )

    print("=== LlmPlan metrics summary ===")
    print(f"Lines parsed: {len(lines)}")
    print()

    print("Event counts (log lines, not deduplicated requests):")
    for event, count in event_counts.most_common():
        print(f"  {event:<28} {count:>5}")
    print()

    print("Fallback by detail:")
    if total_fallback_events == 0:
        print("  (no fallback events)")
    else:
        for detail, count in fallback_by_detail.most_common():
            print_bar(detail, count, total_fallback_events)
    print()

    if last_snapshot is not None:
        s = last_snapshot
        tagged = s.tagged
        print("Latest cumulative snapshot (from last matching line):")
        print(f"  tagged={tagged}  success={s.success}  errors={s.errors}  fallback={s.fallback}")
        print(f"  fallback_rate={s.fallback_rate_percent:.1f}%  success_rate={s.success_rate_percent:.1f}%")
        print(f"  direct_structure={s.direct_structure}  structure_after_fallback={s.structure_after_fallback}")
        print()

        if tagged > 0:
            actionable_rate = 100.0 * actionable / tagged
            policy_count = fallback_by_detail.get("ROUTING_POLICY", 0)
            policy_share = pct(policy_count, total_fallback_events) if total_fallback_events else "n/a"
            print("Derived (see docs/MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md §9):")
            print(f"  actionable_fallback_lines={actionable}  ({pct(actionable, total_fallback_events)} of fallback lines)")
            print(f"  actionable_rate_vs_tagged≈{actionable_rate:.1f}%  (use snapshot tagged for decisions)")
            print(f"  ROUTING_POLICY share of fallback lines={policy_share}")
            if tagged < 50:
                print("  note: tagged < 50 — observe only, avoid routing changes")
            elif tagged < 200:
                print("  note: tagged >= 50 — initial review OK")
            else:
                print("  note: tagged >= 200 — eligible for retirement-candidate review if stable")
    else:
        print("No snapshot fields found on metric lines (unexpected format).", file=sys.stderr)

    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Summarize [LlmPlanMetrics] fallback detail distribution from server logs."
    )
    parser.add_argument(
        "logs",
        nargs="*",
        help="Log file paths or globs (default: run/logs/latest.log). Use '-' for stdin.",
    )
    args = parser.parse_args()

    raw = args.logs if args.logs else ["run/logs/latest.log"]
    paths = resolve_inputs(raw)

    missing = [p for p in paths if p != Path("-") and not p.exists()]
    if missing:
        for path in missing:
            print(f"File not found: {path}", file=sys.stderr)
        return 1

    lines = iter_metric_lines(paths)
    return analyze(lines)


if __name__ == "__main__":
    raise SystemExit(main())
