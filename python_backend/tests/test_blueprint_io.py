"""Read-only blueprint route and file checks."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest
from fastapi import HTTPException

from app.routes import blueprint as bp


@pytest.fixture()
def blueprint_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(bp, "BLUEPRINT_DIR", str(tmp_path))
    monkeypatch.setattr(bp, "MAX_BLUEPRINT_BYTES", 256)
    return tmp_path


def test_blueprint_router_exposes_only_named_read():
    assert [(route.path, route.methods) for route in bp.router.routes] == [
        ("/blueprint/get/{name}", {"GET"}),
    ]


def test_get_reads_existing_blueprint(blueprint_dir):
    value = {"name": "house", "blocks": [1, 2, 3]}
    (blueprint_dir / "house.json").write_text(json.dumps(value), encoding="utf-8")
    assert bp.get_blueprint("house") == value


def test_get_missing_blueprint_does_not_create_storage(blueprint_dir, monkeypatch):
    missing = blueprint_dir / "missing-storage"
    monkeypatch.setattr(bp, "BLUEPRINT_DIR", str(missing))
    with pytest.raises(HTTPException) as exc:
        bp.get_blueprint("house")
    assert exc.value.status_code == 404
    assert not missing.exists()


def test_get_rejects_oversized_on_disk(blueprint_dir):
    path = Path(blueprint_dir) / "huge.json"
    path.write_bytes(b"{" + b"x" * 400 + b"}")
    with pytest.raises(HTTPException) as exc:
        bp.get_blueprint("huge")
    assert exc.value.status_code == 413


def test_get_rejects_symlink(blueprint_dir):
    real = Path(blueprint_dir) / "real.json"
    real.write_text('{"name":"real"}', encoding="utf-8")
    link = Path(blueprint_dir) / "linked.json"
    try:
        os.symlink(real, link)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable on this platform")
    with pytest.raises(HTTPException) as exc:
        bp.get_blueprint("linked")
    assert exc.value.status_code == 400
