from fastapi import APIRouter, HTTPException
from typing import List, Dict, Any
import os
import json
import tempfile

router = APIRouter()

# 蓝图存储目录（相对于 Python 后端根目录）
BLUEPRINT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "blueprints")
MAX_BLUEPRINT_BYTES = 2 * 1024 * 1024


def ensure_blueprint_dir():
    """确保蓝图目录存在"""
    if not os.path.exists(BLUEPRINT_DIR):
        os.makedirs(BLUEPRINT_DIR, exist_ok=True)


def _sanitize_name(name: str) -> str:
    sanitized = "".join(c for c in name if c.isalnum() or c in "_-")
    if not sanitized or sanitized != name:
        raise HTTPException(status_code=400, detail="Invalid blueprint name")
    return sanitized


def _blueprint_path(name: str) -> str:
    return os.path.join(BLUEPRINT_DIR, name + ".json")


@router.get("/blueprint/list")
def list_blueprints() -> Dict[str, List[str]]:
    """
    列出所有可用的蓝图
    """
    ensure_blueprint_dir()

    try:
        files = []
        if os.path.exists(BLUEPRINT_DIR):
            for f in os.listdir(BLUEPRINT_DIR):
                if f.endswith(".json"):
                    # 移除 .json 扩展名
                    name = f[:-5]
                    files.append(name)

        return {"blueprints": sorted(files)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to list blueprints: {str(e)}")


@router.get("/blueprint/get/{name}")
def get_blueprint(name: str) -> Dict[str, Any]:
    """
    获取指定名称的蓝图
    """
    ensure_blueprint_dir()
    sanitized = _sanitize_name(name)
    path = _blueprint_path(sanitized)

    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail=f"Blueprint not found: {name}")

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


@router.post("/blueprint/save")
def save_blueprint(bp: Dict[str, Any]) -> Dict[str, bool]:
    """
    保存蓝图（原子落盘：写 .tmp 后 os.replace）
    """
    ensure_blueprint_dir()

    name = bp.get("name")
    if not name:
        raise HTTPException(status_code=400, detail="Blueprint name is required")

    sanitized = _sanitize_name(str(name))
    path = _blueprint_path(sanitized)

    try:
        payload = json.dumps(bp, indent=2, ensure_ascii=False)
        encoded = payload.encode("utf-8")
        if len(encoded) > MAX_BLUEPRINT_BYTES:
            raise HTTPException(status_code=413, detail="Blueprint payload too large")

        fd, tmp_path = tempfile.mkstemp(
            prefix=f".{sanitized}.",
            suffix=".tmp",
            dir=BLUEPRINT_DIR,
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(payload)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_path, path)
        except Exception:
            try:
                if os.path.exists(tmp_path):
                    os.remove(tmp_path)
            except OSError:
                pass
            raise
        return {"ok": True}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save blueprint: {str(e)}")


@router.delete("/blueprint/delete/{name}")
def delete_blueprint(name: str) -> Dict[str, bool]:
    """
    删除蓝图
    """
    ensure_blueprint_dir()
    sanitized = _sanitize_name(name)
    path = _blueprint_path(sanitized)

    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail=f"Blueprint not found: {name}")

    try:
        os.remove(path)
        return {"ok": True}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to delete blueprint: {str(e)}")
