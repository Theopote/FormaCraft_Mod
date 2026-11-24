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


def test_save_rejects_when_at_blueprint_limit(blueprint_dir, monkeypatch):
    monkeypatch.setattr(bp, "MAX_BLUEPRINTS", 2)
    bp.save_blueprint({"name": "a", "v": 1})
    bp.save_blueprint({"name": "b", "v": 1})
    with pytest.raises(HTTPException) as exc:
        bp.save_blueprint({"name": "c", "v": 1})
    assert exc.value.status_code == 507
    # Overwrite of an existing name still allowed
    bp.save_blueprint({"name": "a", "v": 2})
    assert json.loads((Path(blueprint_dir) / "a.json").read_text(encoding="utf-8"))["v"] == 2


def test_get_and_delete_reject_symlink(blueprint_dir):
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
    with pytest.raises(HTTPException) as exc2:
        bp.delete_blueprint("linked")
    assert exc2.value.status_code == 400
    assert "linked" not in bp.list_blueprints()["blueprints"]
