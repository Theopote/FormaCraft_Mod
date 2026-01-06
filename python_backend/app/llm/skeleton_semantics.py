"""
SkeletonType 语义说明（Prompt 专用）

这是 FormaCraft 的"AI 可理解的空间语言（Spatial Grammar）"的核心。

设计理念：
- SkeletonType 是 AI 在"动手之前"的思考方式，而不是画方块的方式
- 让 LLM 在"规划阶段"就选对空间组织方式，而不是靠生成时瞎试
"""

SKELETON_SEMANTICS = """
--- Available Skeleton Types ---
SkeletonType represents spatial organization patterns for AI planning, not block placement methods.
Each skeleton type represents a way of organizing space, used for decision-making in the planning phase.

When planning a building or structure, you should consider which SkeletonType best matches the spatial organization:

LINEAR_PATH
Semantic: Linear axis skeleton
Spatial meaning: Structure extends along a clear straight line with strong directionality and front-back relationship
Use cases: Roads, straight Great Wall segments, long corridors, axis-type buildings, defensive walls
Constraints: Single main direction, relatively fixed width, length much greater than width
Prompt phrase: "a linear structure extending along a straight axis"

PATH_POLYLINE
Semantic: Polyline axis skeleton
Spatial meaning: Extends along a path with multiple direction changes, usually to adapt to complex terrain
Use cases: Mountain roads, polyline city walls, winding streets
Constraints: Multiple turning points, continuous but not straight, usually follows terrain
Prompt phrase: "a polyline path following terrain turns"

CONTOUR_FOLLOW
Semantic: Contour-following skeleton
Spatial meaning: Extends along terrain contour lines, minimizing vertical changes
Use cases: Mountain roads, Great Wall mountain segments, hillside building connection lines
Constraints: Strong dependency on terrain sampling, gentle height changes, obvious horizontal extension
Prompt phrase: "following terrain contour lines with minimal vertical changes"

RADIAL_RING
Semantic: Central closed ring skeleton
Spatial meaning: Forms a complete closed loop around a center point with strong centripetal and enclosing feeling
Use cases: Fujian Tulou, circular fortresses, circular arenas
Constraints: Closed geometry, clear central space, extremely strong symmetry
Prompt phrase: "a closed radial ring surrounding a central courtyard"

RADIAL_SPOKE
Semantic: Central radial skeleton
Spatial meaning: Multiple axes radiate outward from the center, center position is extremely important
Use cases: Temple of Heaven, central squares, radial city structures
Constraints: Central anchor point, multi-directional connections, often combined with symmetry
Prompt phrase: "radial spokes extending outward from a central anchor"

VERTICAL_STACK
Semantic: Vertical stacking skeleton
Spatial meaning: Multiple layers stacked upward with clear floor logic
Use cases: Residential buildings, towers, office buildings
Constraints: Clear hierarchy, height-dominant, relatively stable base area
Prompt phrase: "vertically stacked layers forming multiple floors"

VERTICAL_TAPER
Semantic: Upward tapering skeleton
Spatial meaning: Gradually narrows toward the top with strong commemorative or symbolic meaning
Use cases: Pagodas, spires, monuments
Constraints: Height-dominant, gradually shrinking layers, often accompanied by symmetry
Prompt phrase: "a vertically tapering structure narrowing toward the top"

GRID
Semantic: Regular grid skeleton
Spatial meaning: Orthogonal, modular layout, highly rational and ordered
Use cases: City blocks, office parks, modern residential clusters
Constraints: X/Z orthogonal, module repetition, easy to extend
Prompt phrase: "an orthogonal grid-based layout"

COURTYARD
Semantic: Courtyard enclosure skeleton
Spatial meaning: Buildings arranged around a central empty courtyard, but not necessarily circular
Use cases: Siheyuan (Chinese courtyard houses), monasteries, office courtyards
Constraints: Inward-facing space, central void, surrounding enclosure
Prompt phrase: "buildings enclosing a central courtyard"

PERIMETER_LOOP
Semantic: Perimeter loop skeleton
Spatial meaning: Forms a closed structure following a specified perimeter, shape can be irregular
Use cases: City walls, park fences, custom outline buildings
Constraints: Closed, follows user-drawn outline, strongly constrains area boundaries
Prompt phrase: "a closed loop following a predefined perimeter outline"

ENCLOSURE
Semantic: Irregular enclosure skeleton
Spatial meaning: Encloses space but allows gaps or incomplete closure, more free and natural
Use cases: Mountain villages, defensive settlements, semi-enclosed parks
Constraints: Non-regular, partial enclosure, strong terrain dependency
Prompt phrase: "an irregular enclosing structure adapting to terrain"

SPAN_SUSPENSION
Semantic: Spanning/suspension skeleton
Spatial meaning: Spans obstacles with minimal or no direct support in the middle
Use cases: Bridges, suspension structures, elevated passages
Constraints: Clear start/end points, suspended middle section, strong structural sense
Prompt phrase: "a spanning structure bridging two distant points"

TERRACED
Semantic: Terraced skeleton
Spatial meaning: Multiple height platforms step along the terrain, strongly coupled with terrain height
Use cases: Mountain cities, terraced buildings, mountain temples
Constraints: Multiple height levels, local leveling, built following terrain
Prompt phrase: "terraced platforms stepping along the terrain"

HIERARCHICAL_TREE
Semantic: Hierarchical tree skeleton
Spatial meaning: Clear "core + subsidiary" relationship with unequal spatial weights
Use cases: Temple complexes, campuses, park planning
Constraints: Dominant main body, subsidiary bodies surrounding, clear structure
Prompt phrase: "a hierarchical layout with a dominant central structure and secondary branches"

COMPOUND
Semantic: Compound skeleton (fallback)
Spatial meaning: Combination of multiple Skeletons for complex or mixed buildings
Use cases: Cities, super-large building complexes, AI free planning
Constraints: Contains multiple sub-Skeletons, requires further decomposition
Prompt phrase: "a compound structure combining multiple spatial skeletons"

--- Important Note ---
SkeletonType is how AI thinks BEFORE taking action, not how to place blocks.
When generating BuildingSpec, consider which SkeletonType best matches the spatial organization pattern described by the user.
You can indicate the preferred SkeletonType in extra.skeletonType if it helps clarify the spatial intent.
"""


def get_skeleton_semantics_prompt() -> str:
    """获取 SkeletonType 语义说明（用于添加到 System Prompt）"""
    return SKELETON_SEMANTICS

