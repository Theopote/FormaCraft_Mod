from fastapi import APIRouter, HTTPException
from typing import Dict, Any

from ..models.edit_request import CityEditRequest, BuildingEditRequest
from ..models.city_spec import CitySpec
from ..models.building_spec import BuildingSpec
from ..services.ai_editor import edit_city_spec, edit_building_spec

router = APIRouter()


@router.post("/edit/city")
async def edit_city_endpoint(req: CityEditRequest) -> Dict[str, Any]:
    """
    增量编辑城市规格
    
    接收当前 CitySpec 和编辑指令，返回更新后的 CitySpec
    """
    try:
        # 将字典转换为 CitySpec 对象
        current = CitySpec.model_validate(req.currentCitySpec)
        
        # 调用 AI 编辑器
        updated = edit_city_spec(
            current,
            req.editCommand,
            api_key=req.apiKey,
            model=req.model,
            temperature=req.temperature,
        )
        
        # 返回更新后的 CitySpec（转换为字典）
        return {
            "updatedCitySpec": updated.model_dump(),
            "notes": f"City '{updated.cityName}' updated successfully."
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to edit city: {str(e)}")


@router.post("/edit/building")
async def edit_building_endpoint(req: BuildingEditRequest) -> Dict[str, Any]:
    """
    增量编辑建筑规格
    
    接收当前 BuildingSpec 和编辑指令，返回更新后的 BuildingSpec
    """
    try:
        # 将字典转换为 BuildingSpec 对象
        current = BuildingSpec.model_validate(req.currentBuildingSpec)
        
        # 调用 AI 编辑器
        updated = edit_building_spec(
            current,
            req.editCommand,
            api_key=req.apiKey,
            model=req.model,
            temperature=req.temperature,
        )
        
        # 返回更新后的 BuildingSpec（转换为字典）
        return {
            "updatedBuildingSpec": updated.model_dump(),
            "notes": f"Building '{updated.type}' updated successfully."
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to edit building: {str(e)}")

