import os
import subprocess
import sys
from types import SimpleNamespace

import pytest

from app.services import inference_call as policy


@pytest.fixture
def budgets(monkeypatch):
    for name, value in {"SEC": 45, "DEEPSEEK_SEC": 60, "REASONER_SEC": 120,
                        "LLMPLAN_SEC": 180, "COMPOSITE_SEC": 600, "CITY_SEC": 600}.items():
        monkeypatch.setattr(policy, "_LLM_CALL_TIMEOUT_" + name, value)


@pytest.mark.parametrize("provider,model,expected", [
    (None, None, 45), ("openai", "reasoner", 45),
    (" DeepSeek ", "chat", 60), ("deepseek", " DeepSeek-REASONER ", 120),
])
def test_provider_model_budgets(budgets, provider, model, expected):
    assert policy._resolve_timeout_sec(SimpleNamespace(llmProvider=provider), model) == expected


@pytest.mark.parametrize("task,expected", [("building", 120), (" CITY ", 600),
                                         ("composite", 600), ("llmplan", 180), ("unknown", 120)])
def test_task_budget_is_at_least_provider_budget(budgets, task, expected):
    assert policy._resolve_timeout_sec_for_task(task, SimpleNamespace(llmProvider="deepseek"), "reasoner") == expected


def test_global_budget_remains_lower_bound(budgets, monkeypatch):
    monkeypatch.setattr(policy, "_LLM_CALL_TIMEOUT_SEC", 900)
    assert policy._resolve_timeout_sec_for_task("city", None, None) == 900


def test_environment_override_is_loaded_on_import():
    env = dict(os.environ)
    env["LLM_CALL_TIMEOUT_SEC"] = "777"
    output = subprocess.check_output(
        [sys.executable, "-c", "from app.services.inference_call import _resolve_timeout_sec; print(_resolve_timeout_sec(None, None))"],
        env=env, text=True,
    )
    assert output.strip() == "777.0"


def test_planner_compatibility_aliases():
    from app.services import ai_planner

    assert ai_planner._resolve_timeout_sec is policy._resolve_timeout_sec
    assert ai_planner._resolve_timeout_sec_for_task is policy._resolve_timeout_sec_for_task


def test_default_timeout_pool_scales_with_build_concurrency(monkeypatch):
    monkeypatch.delenv("LLM_TIMEOUT_POOL_WORKERS", raising=False)
    monkeypatch.setenv("BUILD_MAX_CONCURRENT_REQUESTS", "4")
    assert policy._default_timeout_pool_workers() == 12
    monkeypatch.setenv("BUILD_MAX_CONCURRENT_REQUESTS", "8")
    assert policy._default_timeout_pool_workers() == 20
    monkeypatch.setenv("LLM_TIMEOUT_POOL_WORKERS", "3")
    assert policy._default_timeout_pool_workers() == 3


def test_nested_call_with_timeout_does_not_take_second_slot():
    seen = []

    def outer():
        seen.append("outer")
        return policy.call_with_timeout(lambda: seen.append("inner") or "ok", 5)

    assert policy.call_with_timeout(outer, 5) == "ok"
    assert seen == ["outer", "inner"]
