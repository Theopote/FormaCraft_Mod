"""Blueprint route I/O: size cap + atomic save."""

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


def test_save_rejects_oversized_payload(blueprint_dir):
    with pytest.raises(HTTPException) as exc:
        bp.save_blueprint({"name": "big", "data": "x" * 400})
    assert exc.value.status_code == 413
    assert not (Path(blueprint_dir) / "big.json").exists()


def test_save_is_atomic_replace(blueprint_dir):
    bp.save_blueprint({"name": "house", "blocks": [1, 2, 3]})
    path = Path(blueprint_dir) / "house.json"
    assert path.is_file()
    assert json.loads(path.read_text(encoding="utf-8"))["blocks"] == [1, 2, 3]
    # no leftover tmp files
    leftovers = [p for p in Path(blueprint_dir).iterdir() if p.suffix == ".tmp"]
    assert leftovers == []


def test_get_rejects_oversized_on_disk(blueprint_dir):
    path = Path(blueprint_dir) / "huge.json"
    path.write_bytes(b"{" + b"x" * 400 + b"}")
    with pytest.raises(HTTPException) as exc:
        bp.get_blueprint("huge")
    assert exc.value.status_code == 413
