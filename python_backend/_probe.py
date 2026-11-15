from app.models.building_profile import BuildingProfile, ProfileMinecraftStrategy
from app.services.building_research_agent import finalize_profile_minecraft_strategy
from app.services.building_plan_stage import build_plan_stage_user_block

p = finalize_profile_minecraft_strategy(
    BuildingProfile(query="盖一座天坛", minecraft_strategy=ProfileMinecraftStrategy()), "盖一座天坛")
block = build_plan_stage_user_block(p, "盖一座天坛")
print("=== BLOCK ===")
print(block[:3000])
print("=== contains typology:", "typology" in block.lower())
print("=== contains STAGE P:", "STAGE P" in block)
print("=== contains OPEN-WORLD:", "OPEN-WORLD RESEARCH OVERRIDE" in block)
print("=== contains 建造天坛:", "建造天坛" in block)
