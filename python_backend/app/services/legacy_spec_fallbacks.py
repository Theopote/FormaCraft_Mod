"""Offline rule fallbacks for frozen BuildingSpec/city/composite routes.

These remain compatibility behavior and are not a fallback for LlmPlan failures.
"""

from ..models.request import BuildRequest
from ..models.building_spec import BuildingSpec, BuildingType, BuildingStyle, Footprint, Materials, Features, StyleOptions
from ..models.composite_spec import CompositeSpec, SubStructure, Vec3i, PathSpec
from ..models.city_spec import CitySpec, Zone, StructurePlan, Point
from ..models.path_spec import PathSpec as CityPathSpec
from .legacy_spec_genome import _ensure_genome_for_spec, _ensure_genome_for_city_spec, _ensure_genome_for_composite_spec


def _generate_fallback_spec(req: BuildRequest) -> BuildingSpec:
    """规则基础的回退方案（当 LLM 不可用时）"""
    request_lower = req.requestText.lower()
    
    # 判断建筑类型
    if "塔" in req.requestText or "tower" in request_lower:
        building_type = BuildingType.TOWER
        height = 20
        footprint = Footprint(shape="circle", radius=6)
    elif "桥" in req.requestText or "bridge" in request_lower:
        building_type = BuildingType.BRIDGE
        height = 1
        footprint = Footprint(shape="rectangle", width=10, depth=3)
    elif "房子" in req.requestText or "house" in request_lower or "房屋" in req.requestText:
        building_type = BuildingType.HOUSE
        height = 4
        footprint = Footprint(shape="rectangle", width=8, depth=6)
    else:
        building_type = BuildingType.HOUSE
        height = 4
        footprint = Footprint(shape="rectangle", width=8, depth=6)
    
    # 判断风格
    style = BuildingStyle.MEDIEVAL
    if "现代" in req.requestText or "modern" in request_lower:
        style = BuildingStyle.MODERN
    elif "乡村" in req.requestText or "rustic" in request_lower:
        style = BuildingStyle.RUSTIC
    
    # 判断材料
    wall_material = "minecraft:stone_bricks"
    if "木头" in req.requestText or "wood" in request_lower or "木" in req.requestText:
        wall_material = "minecraft:oak_planks"
    elif "砖" in req.requestText or "brick" in request_lower:
        wall_material = "minecraft:bricks"
    
    materials = Materials(
        wall=wall_material,
        roof="minecraft:dark_oak_planks",
        floor="minecraft:oak_planks",
        window="minecraft:glass_pane"
    )
    
    features = Features(
        hasWindows=True,
        hasStairs=True,
        hasDoor=True,
        hasRoof=True,
        windowCount=2,
        floorCount=1
    )
    
    # 根据建筑类型设置 styleOptions
    if building_type == BuildingType.BRIDGE:
        style_options = StyleOptions(
            bridgeType="arched" if "拱" in req.requestText or "arch" in request_lower else "flat"
        )
    elif building_type == BuildingType.TOWER:
        style_options = StyleOptions(
            doorStyle="none" if "瞭望" in req.requestText else "single",
            roofType="cone" if "圆锥" in req.requestText or "cone" in request_lower else "flat",
            windowRatio=0.3
        )
    else:  # HOUSE
        style_options = StyleOptions(
            doorStyle="double" if "双" in req.requestText or "double" in request_lower else "single",
            roofType="gable" if "双坡" in req.requestText or "gable" in request_lower else "flat",
            windowRatio=0.35
        )
    
    spec = BuildingSpec(
        type=building_type,
        style=style,
        footprint=footprint,
        height=height,
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"根据规则生成的{building_type.value}建筑"
    )
    return _ensure_genome_for_spec(spec, req)


def _generate_fallback_city_spec(req: BuildRequest) -> CitySpec:
    """规则基础的回退方案（生成简单的城市结构）"""
    base_spec = _generate_fallback_spec(req)
    
    # 创建简单的城市布局
    zones = [
        Zone(
            name="central_plaza",
            type="PLAZA",
            radius=15,
            center=Point(x=0, y=0, z=0)
        ),
        Zone(
            name="residential_quarter",
            type="RESIDENTIAL",
            radius=25,
            center=Point(x=40, y=0, z=-20)
        )
    ]
    
    # 创建一些建筑
    house_spec = BuildingSpec(
        type=BuildingType.HOUSE,
        style=base_spec.style,
        footprint=Footprint(shape="rectangle", width=8, depth=6),
        height=4,
        floors=1,
        materials=base_spec.materials,
        features=base_spec.features,
        styleOptions=base_spec.styleOptions
    )
    
    tower_spec = BuildingSpec(
        type=BuildingType.TOWER,
        style=base_spec.style,
        footprint=Footprint(shape="circle", radius=5),
        height=12,
        floors=2,
        materials=base_spec.materials,
        features=base_spec.features,
        styleOptions=base_spec.styleOptions
    )
    
    structures = [
        StructurePlan(
            id="house_1",
            type="HOUSE",
            spec=house_spec,
            offset=Point(x=12, y=0, z=-5),
            zone="residential_quarter"
        ),
        StructurePlan(
            id="house_2",
            type="HOUSE",
            spec=house_spec,
            offset=Point(x=15, y=0, z=-8),
            zone="residential_quarter"
        ),
        StructurePlan(
            id="tower_1",
            type="TOWER",
            spec=tower_spec,
            offset=Point(x=-10, y=0, z=20),
            zone="central_plaza"
        )
    ]
    
    # 创建道路
    roads = [
        CityPathSpec(
            from_pos=Vec3i(x=0, y=0, z=0),
            to_pos=Vec3i(x=40, y=0, z=-20),
            width=5,
            material="minecraft:gravel",
            style="default"
        )
    ]
    
    # 创建桥梁（可选）
    bridges = []
    
    spec = CitySpec(
        cityName="Fallback City",
        style=base_spec.style.value if hasattr(base_spec.style, 'value') else str(base_spec.style),
        size="MEDIUM",
        biome="plains",
        zones=zones,
        structures=structures,
        roads=roads,
        bridges=bridges
    )
    return _ensure_genome_for_city_spec(spec, req)


def _generate_fallback_composite_spec(req: BuildRequest) -> CompositeSpec:
    """规则基础的回退方案（生成简单的复合结构）"""
    request_lower = req.requestText.lower()
    
    structures = []
    paths = []
    
    # 根据关键词生成不同的复合结构
    if "要塞" in req.requestText or "fort" in request_lower:
        # 要塞：中心大厅 + 4 个角塔
        base_spec = _generate_fallback_spec(req)
        house_spec = BuildingSpec(
            type=BuildingType.HOUSE,
            style=base_spec.style,
            footprint=Footprint(shape="rectangle", width=12, depth=10),
            height=6,
            floors=2,
            materials=base_spec.materials,
            features=base_spec.features,
            styleOptions=base_spec.styleOptions
        )
        tower_spec = BuildingSpec(
            type=BuildingType.TOWER,
            style=base_spec.style,
            footprint=Footprint(shape="circle", radius=5),
            height=15,
            floors=3,
            materials=base_spec.materials,
            features=base_spec.features,
            styleOptions=base_spec.styleOptions
        )
        
        structures.extend([
            SubStructure(
                type="HOUSE",
                spec=house_spec,
                offset=Vec3i(x=0, y=0, z=0)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=-20, y=0, z=-20)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=20, y=0, z=-20)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=-20, y=0, z=20)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=20, y=0, z=20)
            ),
        ])
        
        # 生成连接路径（连接中心大厅到各个角塔）
        paths = [
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=-20, y=0, z=-20),
                width=3,
                material="minecraft:gravel",
                style="default"
            ),
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=20, y=0, z=-20),
                width=3,
                material="minecraft:gravel",
                style="default"
            ),
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=-20, y=0, z=20),
                width=3,
                material="minecraft:gravel",
                style="default"
            ),
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=20, y=0, z=20),
                width=3,
                material="minecraft:gravel",
                style="default"
            ),
        ]
    else:
        # 默认：简单的房屋 + 塔楼组合
        base_spec = _generate_fallback_spec(req)
        house_spec = BuildingSpec(
            type=BuildingType.HOUSE,
            style=base_spec.style,
            footprint=Footprint(shape="rectangle", width=8, depth=6),
            height=4,
            floors=1,
            materials=base_spec.materials,
            features=base_spec.features,
            styleOptions=base_spec.styleOptions
        )
        tower_spec = BuildingSpec(
            type=BuildingType.TOWER,
            style=base_spec.style,
            footprint=Footprint(shape="circle", radius=5),
            height=12,
            floors=2,
            materials=base_spec.materials,
            features=base_spec.features,
            styleOptions=base_spec.styleOptions
        )
        
        structures.extend([
            SubStructure(
                type="HOUSE",
                spec=house_spec,
                offset=Vec3i(x=0, y=0, z=0)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=20, y=0, z=0)
            ),
        ])
        
        # 生成连接路径
        paths = [
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=20, y=0, z=0),
                width=3,
                material="minecraft:gravel",
                style="default"
            )
        ]
    
    spec = CompositeSpec(structures=structures, paths=paths)
    return _ensure_genome_for_composite_spec(spec, req)
