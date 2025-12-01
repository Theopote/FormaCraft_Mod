from fastapi import FastAPI

from .agents import AIPipeline
from .models import BuildingRequestModel, BuildingResponseModel

app = FastAPI(title="FormaCraft AI Backend")
pipeline = AIPipeline()


@app.post("/api/v1/generate_building_plan", response_model=BuildingResponseModel)
async def generate_building_plan(request: BuildingRequestModel) -> BuildingResponseModel:
    # TODO: 在这里接入真实的大语言模型，替换掉 AIPipeline 内部的占位实现
    return pipeline.run(request)
