"""FormaCraft CI 质量门 — 离线回归基准（无需 LLM API key）。

在 python_backend 目录下：

    python -m eval.ci_gate              # 完整 CI 门（默认 --gate）
    python -m eval.ci_gate --quick    # 跳过 unittest 子进程（本地快检）

步骤：
1. 关键单元测试（unittest）
2. golden_eval --scenarios --gate --ci-only
3. diversity_eval --diversity-scenarios --gate（仅 ci:true 场景）
4. diversity 负向检测（重复 plan 应被检出）
5. ShapeLibrary / PRIMITIVE few-shot schema 校验
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import List, Optional, Tuple

_THIS = Path(__file__).resolve()
_BACKEND_ROOT = _THIS.parent.parent
if str(_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_BACKEND_ROOT))

_UNITTEST_MODULES: Tuple[str, ...] = (
    "tests.test_cottage_castle_p0",
    "tests.test_siheyuan_courtyard_p0",
    "tests.test_square_tower_p0",
    "tests.test_temple_of_heaven_p0",
    "tests.test_famen_foguang_p0",
    "tests.test_typology_migration",
    "tests.test_typology_plan_repair",
    "tests.test_legacy_module_tightening",
    "tests.test_pr_a_culture_prompt",
    "tests.test_patch_edit_p0",
    "tests.test_building_research_agent",
    "tests.test_reference_blueprint",
    "tests.test_landmark_routing_architect",
    "tests.test_url_safety",
    "tests.test_building_plan_stage",
    "tests.test_vision_reference",
    "tests.test_llm_plan_symmetry",
    "tests.test_llm_plan_repair",
    "tests.test_plan_architectural_enrichment",
    "tests.test_llm_error_humanizer",
    "tests.test_diversity_eval",
    "tests.test_shape_primitive_m1",
    "tests.test_shape_primitive_m2",
    "tests.test_shape_primitive_m3",
    "tests.test_shape_advanced_m3plus",
    "tests.test_assembly_plan_validator",
    "tests.test_assembly_plan_repair",
    "tests.test_golden_assembly_capability_gap",
)


def _run_unittest_modules(modules: Tuple[str, ...]) -> int:
    print("\n========== [1/5] 单元测试 ==========")
    cmd = [sys.executable, "-m", "unittest", *modules, "-v"]
    proc = subprocess.run(cmd, cwd=str(_BACKEND_ROOT))
    return proc.returncode


def run_diversity_negative() -> int:
    """负向：重复 plan 应无法通过 diversity 阈值（unique_ratio < 1）。"""
    from eval.diversity_eval import (
        DEFAULT_THRESHOLDS,
        DiversityThresholds,
        _load_plan_file,
        evaluate_diversity,
    )

    print("\n========== [4/5] Diversity 负向检测 ==========")
    fixtures = _THIS.parent / "fixtures" / "diversity" / "stadium"
    paths = [fixtures / "module_seed_100.json", fixtures / "module_seed_100_dup.json"]
    plans = []
    for p in paths:
        plan = _load_plan_file(p)
        if plan:
            plans.append(plan)
    if len(plans) < 2:
        print("负向 fixture 缺失")
        return 2

    th = DiversityThresholds(
        min_samples=2,
        min_unique_ratio=1.0,
        min_mean_distance=0.01,
        min_routing_unique=1,
        require_routing_diversity=False,
    )
    metrics = evaluate_diversity(plans, label="duplicate_negative", thresholds=th)
    if metrics.unique_ratio >= th.min_unique_ratio:
        print(f"FAIL: 重复 plan 未被 diversity 检出（unique_ratio={metrics.unique_ratio:.3f}）")
        return 1
    print(f"OK: 重复 plan 正确 FAIL（unique_ratio={metrics.unique_ratio:.3f}）")
    return 0


def run_shape_fixtures() -> int:
    """校验 ShapeLibrary PRIMITIVE few-shot / golden plan schema。"""
    print("\n========== [6/6] ShapeLibrary fixture schema ==========")
    from app.models.llm_plan import validate_llm_plan_dict

    repo_root = _BACKEND_ROOT.parent
    candidates = [
        repo_root / "src/main/resources/assets/formacraft/llmplan_examples",
        _THIS.parent / "fixtures" / "plans",
    ]
    patterns = (
        "primitive_cylinder.json",
        "primitive_sphere.json",
        "primitive_csg_box_cylinder.json",
        "primitive_voronoi_plate.json",
        "primitive_voronoi_3d.json",
        "primitive_mobius.json",
        "primitive_mobius_csg.json",
        "assembly_gothic_shell_golden.json",
        "assembly_spiral_preset_golden.json",
        "assembly_bridge_preset_golden.json",
        "assembly_capability_gap_explicit_golden.json",
    )
    errors: List[str] = []
    checked = 0
    for base in candidates:
        if not base.is_dir():
            continue
        for name in patterns:
            path = base / name
            if not path.is_file():
                continue
            checked += 1
            data = json.loads(path.read_text(encoding="utf-8"))
            plan = data.get("plan", data)
            try:
                validate_llm_plan_dict(plan)
            except Exception as exc:
                errors.append(f"{path.name}: {exc}")

    if checked == 0:
        print("未找到 shape fixture 文件")
        return 2
    if errors:
        for e in errors:
            print(f"  FAIL {e}")
        return 1
    print(f"OK: {checked} 个 PRIMITIVE fixture schema 通过")
    return 0


def run_assembly_repair_negative() -> int:
    """负向：无效 graph 端口应通过 finalize 转为 capability_gap。"""
    from eval.golden_eval import _load_plan_file
    from app.services.assembly_plan_repair import finalize_assembly_plan_or_gap

    print("\n========== [5/6] Assembly repair → capability_gap ==========")
    path = _THIS.parent / "fixtures" / "plans" / "assembly_capability_gap_bad_port_golden.json"
    plan = _load_plan_file(path)
    if plan is None:
        print("负向 fixture 缺失")
        return 2
    prompt = plan.get("prompt") if isinstance(plan.get("prompt"), str) else "ASSEMBLY 自由几何"
    out = finalize_assembly_plan_or_gap(plan, prompt)
    if str(out.get("plan_status") or "").lower() != "capability_gap":
        print(f"FAIL: bad port plan 未转为 capability_gap (status={out.get('plan_status')!r})")
        return 1
    gap = out.get("capability_gap") if isinstance(out.get("capability_gap"), dict) else {}
    code = str(gap.get("code") or "")
    if code != "E_CONN_UNKNOWN_PORT":
        print(f"FAIL: 期望 E_CONN_UNKNOWN_PORT，实际 {code!r}")
        return 1
    print("OK: bad port plan 正确转为 capability_gap")
    return 0


def run_ci_gate(*, gate: bool = True, quick: bool = False) -> int:
    steps: List[Tuple[str, int]] = []

    if not quick:
        code = _run_unittest_modules(_UNITTEST_MODULES)
        steps.append(("unittest", code))
        if code != 0:
            _print_summary(steps)
            return code

    print("\n========== [2/5] Golden scenarios ==========")
    from eval.golden_eval import run_scenarios
    # gate=False：仅 HARD 失败阻断；typology 关键项已通过 promote_scenario_hard_checks 升为 HARD
    code = run_scenarios(gate=False, ci_only=True)
    steps.append(("golden_scenarios", code))
    if code != 0:
        _print_summary(steps)
        return code

    print("\n========== [3/5] Diversity scenarios (ci) ==========")
    from eval.diversity_eval import run_diversity_scenarios
    code = run_diversity_scenarios(gate=gate, ci_only=True)
    steps.append(("diversity_ci", code))
    if code != 0:
        _print_summary(steps)
        return code

    code = run_diversity_negative()
    steps.append(("diversity_negative", code))
    if code != 0:
        _print_summary(steps)
        return code

    code = run_assembly_repair_negative()
    steps.append(("assembly_repair_negative", code))
    if code != 0:
        _print_summary(steps)
        return code

    code = run_shape_fixtures()
    steps.append(("shape_fixtures", code))
    _print_summary(steps)
    return code


def _print_summary(steps: List[Tuple[str, int]]) -> None:
    print("\n========== CI Gate 汇总 ==========")
    for name, code in steps:
        mark = "PASS" if code == 0 else "FAIL"
        print(f"  [{mark}] {name} (exit={code})")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="FormaCraft CI 质量门")
    parser.add_argument("--no-gate", action="store_true",
                        help="SOFT 告警不计入失败（默认 gate 开启）")
    parser.add_argument("--quick", action="store_true",
                        help="跳过 unittest 子进程")
    args = parser.parse_args(argv)
    return run_ci_gate(gate=not args.no_gate, quick=args.quick)


if __name__ == "__main__":
    raise SystemExit(main())
