from fastapi import APIRouter, HTTPException
from typing import Dict, Any
import os
import json

router = APIRouter()

# 蓝图存储目录（相对于 Python 后端根目录）
BLUEPRINT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "blueprints")
MAX_BLUEPRINT_BYTES = 2 * 1024 * 1024


def _sanitize_name(name: str) -> str:
    sanitized = "".join(c for c in name if c.isalnum() or c in "_-")
    if not sanitized or sanitized != name:
        raise HTTPException(status_code=400, detail="Invalid blueprint name")
    return sanitized


def _blueprint_path(name: str) -> str:
    return os.path.join(BLUEPRINT_DIR, name + ".json")


def _assert_regular_file(path: str) -> None:
    """Reject symlinks and non-files so reads cannot escape via link targets."""
    if os.path.islink(path):
        raise HTTPException(status_code=400, detail="Blueprint path must not be a symlink")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Blueprint not found")
    if not os.path.isfile(path):
        raise HTTPException(status_code=400, detail="Blueprint path is not a regular file")


@router.get("/blueprint/get/{name}")
def get_blueprint(name: str) -> Dict[str, Any]:
    """
    获取指定名称的蓝图
    """
    sanitized = _sanitize_name(name)
    path = _blueprint_path(sanitized)
    _assert_regular_file(path)

    try:
        size = os.path.getsize(path)
        if size > MAX_BLUEPRINT_BYTES:
            raise HTTPException(status_code=413, detail="Blueprint file too large")
        with open(path, "r", encoding="utf-8") as f:
            blueprint = json.load(f)
        return blueprint
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to read blueprint: {str(e)}")
