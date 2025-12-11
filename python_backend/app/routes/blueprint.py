from fastapi import APIRouter, HTTPException
from typing import List, Dict, Any
import os
import json

router = APIRouter()

# 蓝图存储目录（相对于 Python 后端根目录）
BLUEPRINT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "blueprints")


def ensure_blueprint_dir():
    """确保蓝图目录存在"""
    if not os.path.exists(BLUEPRINT_DIR):
        os.makedirs(BLUEPRINT_DIR, exist_ok=True)


@router.get("/blueprint/list")
async def list_blueprints() -> Dict[str, List[str]]:
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
async def get_blueprint(name: str) -> Dict[str, Any]:
    """
    获取指定名称的蓝图
    """
    ensure_blueprint_dir()
    
    # 验证名称（防止路径遍历攻击）
    sanitized = "".join(c for c in name if c.isalnum() or c in "_-")
    if not sanitized or sanitized != name:
        raise HTTPException(status_code=400, detail="Invalid blueprint name")
    
    path = os.path.join(BLUEPRINT_DIR, name + ".json")
    
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail=f"Blueprint not found: {name}")
    
    try:
        with open(path, "r", encoding="utf-8") as f:
            blueprint = json.load(f)
        return blueprint
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to read blueprint: {str(e)}")


@router.post("/blueprint/save")
async def save_blueprint(bp: Dict[str, Any]) -> Dict[str, bool]:
    """
    保存蓝图
    """
    ensure_blueprint_dir()
    
    name = bp.get("name")
    if not name:
        raise HTTPException(status_code=400, detail="Blueprint name is required")
    
    # 验证名称
    sanitized = "".join(c for c in name if c.isalnum() or c in "_-")
    if not sanitized or sanitized != name:
        raise HTTPException(status_code=400, detail="Invalid blueprint name")
    
    path = os.path.join(BLUEPRINT_DIR, name + ".json")
    
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(bp, f, indent=2, ensure_ascii=False)
        return {"ok": True}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save blueprint: {str(e)}")


@router.delete("/blueprint/delete/{name}")
async def delete_blueprint(name: str) -> Dict[str, bool]:
    """
    删除蓝图
    """
    ensure_blueprint_dir()
    
    # 验证名称
    sanitized = "".join(c for c in name if c.isalnum() or c in "_-")
    if not sanitized or sanitized != name:
        raise HTTPException(status_code=400, detail="Invalid blueprint name")
    
    path = os.path.join(BLUEPRINT_DIR, name + ".json")
    
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail=f"Blueprint not found: {name}")
    
    try:
        os.remove(path)
        return {"ok": True}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to delete blueprint: {str(e)}")

