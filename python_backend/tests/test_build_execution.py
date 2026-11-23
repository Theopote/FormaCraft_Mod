"""Exercise real ASGI requests with blocked planners; no network/LLM required."""
import asyncio
import threading

import httpx
import pytest
from fastapi import HTTPException

from app.main import app
from app.routes import build
from app.services.planning_execution import PlanningExecutor


class Connection:
    disconnected = False

    async def is_disconnected(self):
        return self.disconnected


def test_request_budget_reaches_nested_inference():
    from app.services.inference_call import call_with_timeout, remaining_call_timeout

    executor = PlanningExecutor(1, 5)
    connection = Connection()
    connection.headers = {"X-FormaCraft-Planning-Seconds": "1"}
    try:
        def generate():
            return call_with_timeout(lambda: remaining_call_timeout(30), 30)

        remaining = asyncio.run(executor.run(connection, generate))
        assert 0 < remaining <= 1
    finally:
        executor.shutdown()


def test_inference_client_inherits_shorter_stage_budget():
    from unittest.mock import Mock
    from app.services.inference_call import bounded_inference_client, call_with_timeout

    provider = Mock()
    result = call_with_timeout(
        lambda: call_with_timeout(lambda: bounded_inference_client(provider, 30), 20), 1,
    )
    assert result is provider.with_options.return_value
    assert 0 < provider.with_options.call_args.kwargs["timeout"] <= 1
    assert provider.with_options.call_args.kwargs["max_retries"] == 0


async def started(event):
    async def poll():
        while not event.is_set():
            await asyncio.sleep(0.005)
    await asyncio.wait_for(poll(), 2)


def test_builds_run_concurrently_and_health_remains_responsive(monkeypatch):
    executor = PlanningExecutor(2, 5)
    monkeypatch.setattr(build, "planning_executor", executor)
    entered = [threading.Event(), threading.Event()]
    release = threading.Event()
    lock = threading.Lock()
    calls = []

    def generate(req):
        with lock:
            index = len(calls)
            calls.append(req)
        entered[index].set()
        assert release.wait(3)
        return {"components": []}

    monkeypatch.setattr(build, "generate_llm_plan", generate)
    async def scenario():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            tasks = [asyncio.create_task(client.post("/build", json={"outputFormat": "llmplan", "requestText": "house"})) for _ in range(2)]
            try:
                await asyncio.gather(*(started(event) for event in entered))
                health = await asyncio.wait_for(client.get("/health"), 1)
                assert health.json() == {"ok": True}
                busy = await client.post("/build", json={"outputFormat": "llmplan", "requestText": "house"})
                assert busy.status_code == 503
                assert busy.headers["Retry-After"] == "1"
            finally:
                release.set()
                responses = await asyncio.gather(*tasks)
            assert all(response.status_code == 200 for response in responses)
            assert all(response.json() == {"components": [], "kind": "llmplan"} for response in responses)

    try:
        asyncio.run(scenario())
    finally:
        release.set()
        executor.shutdown()


@pytest.mark.parametrize("abandon", ["timeout", "disconnect", "cancel"])
def test_abandoned_work_keeps_capacity_until_worker_finishes(abandon):
    executor = PlanningExecutor(1, 0.05 if abandon == "timeout" else 5)
    entered = threading.Event()
    release = threading.Event()
    finished = threading.Event()
    connection = Connection()

    def generate():
        entered.set()
        try:
            assert release.wait(3)
            raise ValueError("late failure must be consumed")
        finally:
            finished.set()

    async def scenario():
        task = asyncio.create_task(executor.run(connection, generate))
        try:
            await started(entered)
            if abandon == "cancel":
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task
            else:
                connection.disconnected = abandon == "disconnect"
                with pytest.raises(HTTPException) as failure:
                    await task
                assert failure.value.status_code == (504 if abandon == "timeout" else 499)
            with pytest.raises(HTTPException) as busy:
                await executor.run(Connection(), lambda: "unexpected")
            assert busy.value.status_code == 503
        finally:
            release.set()
            await started(finished)
        # The worker releases its permit after the callable's finally block.
        for _ in range(100):
            try:
                assert await executor.run(Connection(), lambda: "recovered") == "recovered"
                break
            except HTTPException as error:
                assert error.status_code == 503
                await asyncio.sleep(0.005)
        else:
            pytest.fail("worker capacity was not released")

    try:
        asyncio.run(scenario())
    finally:
        release.set()
        executor.shutdown()


def test_disconnected_request_does_not_start_work():
    executor = PlanningExecutor(1)
    connection = Connection()
    connection.disconnected = True

    async def scenario():
        with pytest.raises(HTTPException) as failure:
            await executor.run(connection, lambda: pytest.fail("must not start"))
        assert failure.value.status_code == 499

    try:
        asyncio.run(scenario())
    finally:
        executor.shutdown()


@pytest.mark.parametrize("output_format,mode,city,composite,kind", [
    ("llmplan", "BUILD", False, False, "llmplan"),
    ("auto", "BUILD", True, True, "llmplan"),
    ("buildingspec", "BUILD", False, False, "buildingspec"),
    ("buildingspec", "BUILD", True, True, "city"),
    ("buildingspec", "BUILD", False, True, "composite"),
    ("auto", "EDIT", True, False, "llmplan"),
    ("auto", "EDIT", False, True, "llmplan"),
    ("auto", "EDIT", False, False, "llmplan"),
])
def test_dispatch_contracts(monkeypatch, output_format, mode, city, composite, kind):
    from types import SimpleNamespace

    monkeypatch.setattr(build, "_maybe_clarify", lambda req: None)
    monkeypatch.setattr(build, "_should_generate_city", lambda req: city)
    monkeypatch.setattr(build, "_should_generate_composite", lambda req: composite)
    for name, result_kind in [("generate_llm_plan", "llmplan"), ("generate_building_spec", "buildingspec"),
                              ("generate_city_spec", "city"), ("generate_composite_spec", "composite")]:
        monkeypatch.setattr(build, name, lambda req, value=result_kind: {"selected": value})
    response = build._generate_response(SimpleNamespace(outputFormat=output_format, promptMode=mode))
    assert response == {"selected": kind, "kind": kind}


def test_clarification_does_not_invoke_generator(monkeypatch):
    from types import SimpleNamespace

    clarification = {"kind": "clarification", "question": "size?"}
    monkeypatch.setattr(build, "_maybe_clarify", lambda req: clarification)
    monkeypatch.setattr(build, "generate_llm_plan", lambda req: pytest.fail("must not generate"))
    assert build._generate_response(SimpleNamespace(outputFormat="llmplan")) == clarification


def test_provider_failure_keeps_502_response(monkeypatch):
    from types import SimpleNamespace

    monkeypatch.setattr(build, "_maybe_clarify", lambda req: None)

    def fail(req):
        raise ValueError("provider failed")

    monkeypatch.setattr(build, "generate_llm_plan", fail)
    with pytest.raises(HTTPException) as failure:
        build._generate_response(SimpleNamespace(outputFormat="llmplan"))
    assert failure.value.status_code == 502
