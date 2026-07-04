"""Weekly live LLM eval — golden prompts + diversity 采样（需 API key）。

在 python_backend 目录下：

    python -m eval.live_eval_weekly
    python -m eval.live_eval_weekly --gate --output reports/live_weekly.json
    python -m eval.live_eval_weekly --full --samples 5

默认跑 eval/fixtures/scenarios.json 中 ci:true 的 prompt（4 条），
外加 stadium diversity 在线采样。Python 侧缺少 Java 组装的系统 prompt，
结果仅作冒烟/趋势监控，不作为 PR 阻断门。

环境：OPENAI_API_KEY，或 LLM_PROVIDER=ollama 等本地 provider。
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

_THIS = Path(__file__).resolve()
_BACKEND_ROOT = _THIS.parent.parent
if str(_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_BACKEND_ROOT))

# patch 类 prompt 需已有 plan 上下文，weekly 默认跳过
_PATCH_PROMPT = "把屋顶换成红色"


def _load_ci_prompts() -> List[str]:
    from eval.golden_eval import _load_scenarios

    prompts: List[str] = []
    seen: set[str] = set()
    for sc in _load_scenarios():
        if not sc.get("ci"):
            continue
        p = (sc.get("prompt") or "").strip()
        if p and p not in seen:
            seen.add(p)
            prompts.append(p)
    return prompts


def _preflight_llm() -> Tuple[bool, str]:
    try:
        from app.services.llm_client import build_config, get_client, is_local_provider
    except Exception as exc:
        return False, f"无法导入 llm_client：{exc}"

    cfg = build_config()
    if is_local_provider(cfg.provider):
        client = get_client()
        if client is None:
            return False, f"本地 provider={cfg.provider} 但无法创建 client（检查 LLM_BASE_URL）"
        return True, f"local provider={cfg.provider} model={cfg.model}"

    if not cfg.api_key:
        return False, "未配置 OPENAI_API_KEY（或请求内 apiKey）；weekly live eval 需要 LLM"
    return True, f"provider={cfg.provider} model={cfg.model}"


def _eval_result_to_dict(res: Any) -> Dict[str, Any]:
    return {
        "label": res.label,
        "ok": res.ok,
        "checks": [asdict(c) for c in res.checks],
        "hard_failures": len(res.hard_failures),
        "soft_failures": len(res.soft_failures),
    }


def _diversity_metrics_to_dict(metrics: Any) -> Dict[str, Any]:
    return {
        "label": metrics.label,
        "sample_count": metrics.sample_count,
        "unique_signatures": metrics.unique_signatures,
        "unique_ratio": metrics.unique_ratio,
        "routing_paths": metrics.routing_paths,
        "routing_unique": metrics.routing_unique,
        "mean_pairwise_distance": metrics.mean_pairwise_distance,
        "min_pairwise_distance": metrics.min_pairwise_distance,
        "max_pairwise_distance": metrics.max_pairwise_distance,
        "ok": metrics.ok,
        "checks": [asdict(c) for c in metrics.checks],
    }


def run_golden_live(
    prompts: List[str],
    *,
    gate: bool,
) -> Tuple[int, List[Any]]:
    from app.models.request import BuildRequest, PlayerInfo, Vec3i, WorldContext
    from app.services.ai_planner import generate_llm_plan
    from eval.golden_eval import EvalResult, Check, evaluate_plan, _print_result, _summarize

    print("\n========== [1/2] Golden live eval ==========")
    results: List[EvalResult] = []
    for prompt in prompts:
        req = BuildRequest(
            player=PlayerInfo(name="weekly_eval", pos=Vec3i(x=0, y=64, z=0), facing="SOUTH"),
            world=WorldContext(dimension="minecraft:overworld", biome="minecraft:plains"),
            requestText=prompt,
            userMessage=prompt,
            promptMode="BUILD",
            outputFormat="llmplan",
        )
        try:
            plan = generate_llm_plan(req)
            results.append(evaluate_plan(plan, label=prompt, prompt=prompt))
        except Exception as exc:
            r = EvalResult(label=prompt)
            r.checks.append(Check("generation", False, hard=True, detail=str(exc)))
            results.append(r)

    for r in results:
        _print_result(r)
    code = _summarize(results, gate)
    return code, results


def run_diversity_live(
    prompt: str,
    *,
    samples: int,
    gate: bool,
    min_unique_ratio: float,
    min_mean_distance: float,
) -> Tuple[int, Optional[Dict[str, Any]]]:
    from app.models.request import BuildRequest, PlayerInfo, Vec3i, WorldContext
    from app.services.ai_planner import generate_llm_plan
    from eval.diversity_eval import (
        DiversityThresholds,
        _components,
        evaluate_diversity,
        routing_path,
        _print_metrics,
    )

    print("\n========== [2/2] Live diversity eval ==========")
    th = DiversityThresholds(
        min_unique_ratio=min_unique_ratio,
        min_mean_distance=min_mean_distance,
    )

    plans: List[Dict[str, Any]] = []
    for i in range(samples):
        req = BuildRequest(
            player=PlayerInfo(name="weekly_diversity", pos=Vec3i(x=0, y=64, z=0), facing="SOUTH"),
            world=WorldContext(dimension="minecraft:overworld", biome="minecraft:plains"),
            requestText=prompt,
            userMessage=prompt,
            promptMode="BUILD",
            outputFormat="llmplan",
        )
        try:
            plan = generate_llm_plan(req)
            if isinstance(plan, dict):
                plans.append(plan)
                print(
                    f"  sample {i + 1}/{samples}: routing={routing_path(plan)} "
                    f"types={[c.get('component_type') for c in _components(plan)]}"
                )
        except Exception as exc:
            print(f"  sample {i + 1}/{samples} FAILED: {exc}")

    if len(plans) < th.min_samples:
        print(f"有效样本不足：{len(plans)}/{samples}")
        return 1, {"prompt": prompt, "samples": samples, "error": "insufficient_samples"}

    metrics = evaluate_diversity(
        plans,
        label=f"live | {prompt[:32]}…",
        thresholds=th,
    )
    _print_metrics(metrics)

    hard_fail = any(c.hard and not c.passed for c in metrics.checks)
    soft_fail = any(not c.hard and not c.passed for c in metrics.checks)
    code = 1 if hard_fail or (gate and soft_fail) else 0
    summary = _diversity_metrics_to_dict(metrics)
    summary["prompt"] = prompt
    summary["samples"] = samples
    summary["exit_code"] = code
    return code, summary


def run_weekly_live(
    *,
    gate: bool = False,
    full: bool = False,
    skip_diversity: bool = False,
    samples: int = 5,
    min_unique_ratio: float = 0.5,
    min_mean_distance: float = 0.12,
    diversity_prompt: Optional[str] = None,
    output: Optional[Path] = None,
) -> int:
    ok, detail = _preflight_llm()
    if not ok:
        print(f"LLM preflight FAIL: {detail}")
        return 2
    print(f"LLM preflight OK: {detail}")

    if full:
        from eval.golden_eval import GOLDEN_PROMPTS

        prompts = list(GOLDEN_PROMPTS)
    else:
        prompts = _load_ci_prompts()
        prompts = [p for p in prompts if p != _PATCH_PROMPT]

    if not prompts:
        print("没有可运行的 golden prompt")
        return 2

    print(f"Golden prompts ({len(prompts)}):")
    for i, p in enumerate(prompts, 1):
        print(f"  {i}. {p}")

    started = datetime.now(timezone.utc).isoformat()
    golden_code, golden_results = run_golden_live(prompts, gate=gate)

    diversity_code = 0
    diversity_summary: Optional[Dict[str, Any]] = None
    if not skip_diversity:
        div_prompt = diversity_prompt or "在锚点位置生成现代风格的椭圆形体育场建筑"
        diversity_code, diversity_summary = run_diversity_live(
            div_prompt,
            samples=samples,
            gate=gate,
            min_unique_ratio=min_unique_ratio,
            min_mean_distance=min_mean_distance,
        )

    finished = datetime.now(timezone.utc).isoformat()
    report: Dict[str, Any] = {
        "kind": "formacraft_weekly_live_eval",
        "started_at": started,
        "finished_at": finished,
        "llm": detail,
        "golden": {
            "prompt_count": len(prompts),
            "exit_code": golden_code,
            "results": [_eval_result_to_dict(r) for r in golden_results],
        },
        "diversity": diversity_summary,
        "overall_exit_code": max(golden_code, diversity_code),
    }

    print("\n========== Weekly live eval 汇总 ==========")
    print(f"  golden exit={golden_code}")
    if not skip_diversity:
        print(f"  diversity exit={diversity_code}")
    print(f"  overall exit={report['overall_exit_code']}")

    if output is not None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  report -> {output}")

    return int(report["overall_exit_code"])


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="FormaCraft weekly live LLM eval")
    parser.add_argument("--gate", action="store_true",
                        help="SOFT 告警也计入失败")
    parser.add_argument("--full", action="store_true",
                        help="跑完整 GOLDEN_PROMPTS（含 patch 类）")
    parser.add_argument("--skip-diversity", action="store_true",
                        help="跳过 diversity 在线采样")
    parser.add_argument("--samples", type=int, default=5,
                        help="diversity 采样次数（默认 5）")
    parser.add_argument("--min-unique-ratio", type=float, default=0.5)
    parser.add_argument("--min-mean-distance", type=float, default=0.12)
    parser.add_argument("--diversity-prompt", type=str,
                        help="覆盖默认 stadium diversity prompt")
    parser.add_argument("--output", type=str,
                        help="JSON 报告路径（默认 eval/reports/live_weekly_<timestamp>.json）")
    args = parser.parse_args(argv)

    if args.output:
        out_path = Path(args.output)
    else:
        ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        out_path = _THIS.parent / "reports" / f"live_weekly_{ts}.json"

    return run_weekly_live(
        gate=args.gate,
        full=args.full,
        skip_diversity=args.skip_diversity,
        samples=args.samples,
        min_unique_ratio=args.min_unique_ratio,
        min_mean_distance=args.min_mean_distance,
        diversity_prompt=args.diversity_prompt,
        output=out_path,
    )


if __name__ == "__main__":
    raise SystemExit(main())
