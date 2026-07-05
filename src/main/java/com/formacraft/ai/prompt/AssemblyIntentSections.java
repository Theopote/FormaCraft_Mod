package com.formacraft.ai.prompt;

import java.util.List;
import java.util.Locale;

/**
 * 检测「自由几何 / ASSEMBLY / 螺旋塔 / 不要地标」类用户意图，并注入强制路由与 few-shot。
 */
public final class AssemblyIntentSections {

    private static final List<String> ASSEMBLY_INTENT_MARKERS = List.of(
            "assembly", "assemble", "metaassembly",
            "螺旋", "螺旋塔", "螺旋楼梯", "瞭望塔", "旋转", "扭转",
            "spiral", "helix", "twist", "watchtower", "lookout",
            "自由几何", "自由形体", "非矩形", "非矩形体量", "异形",
            "freeform", "free-form", "non-rectangular", "non rectangular",
            "diagrid", "exoskeleton", "space frame", "shell box"
    );

    private AssemblyIntentSections() {}

    public static boolean detectsFreeformAssemblyIntent(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String lower = userMessage.toLowerCase(Locale.ROOT);
        if (com.formacraft.common.archetype.LandmarkRoutingPolicy.rejectsLandmarkModule(userMessage)) {
            return true;
        }
        for (String marker : ASSEMBLY_INTENT_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static String promptBlockForIntent(String userMessage) {
        if (!detectsFreeformAssemblyIntent(userMessage)) {
            return "";
        }
        return """

ASSEMBLY INTENT (MANDATORY for this request):
- Output a top-level component with component_type="ASSEMBLY" (NOT nested inside MASS_*).
- Put MetaAssembly payload ONLY in params.assembly { macro?, graph?, ops? }.
- Do NOT put params.assembly inside MASS_MAIN / MASS_SECONDARY — the compiler will reject overlapping geometry.
- Do NOT use MODULE / landmark modules when the user rejects landmarks or asks for original freeform geometry.
- Do NOT stack MASS_MAIN + MASS_SECONDARY + FACADE_WINDOWS + ENTRANCE + ROOF on the same slot for this request.
- Use ONE ASSEMBLY component as the primary volume; add DECOR_DETAIL / SIGNAGE only if needed.
- Slot anchors and relative_position must be RELATIVE to plan.anchor (not world coordinates).

Spiral / twisted tower hint:
- Prefer graph.components[] with type SHELL_BOX and twistTurns (0.25–1.5 full rotations over height).
- Example twistTurns: 0.75 = three-quarter turn from base to top; pair with macro.style.verticality.

FEW-SHOT (spiral lookout tower, no landmark MODULE):
{
  "mode": "build",
  "style_profile": "Gothic_Cathedral",
  "anchor": { "x": 0, "y": 64, "z": 0 },
  "global_constraints": { "facing": "SOUTH", "symmetry": "NONE", "terrain_strategy": "ADAPTIVE" },
  "layout": { "skeleton_type": "COMPOUND", "path_based": false, "slots": [] },
  "components": [
    {
      "component_type": "ASSEMBLY",
      "relative_position": { "x": 0, "y": 0, "z": 0 },
      "dimensions": { "width": 12, "depth": 12, "height": 28 },
      "params": {
        "assembly": {
          "entranceFacing": "SOUTH",
          "macro": {
            "style": { "styleId": "Gothic_Cathedral", "verticality": 0.9, "transparency": 0.4 }
          },
          "graph": {
            "components": [
              {
                "id": "SpiralShell",
                "type": "SHELL_BOX",
                "at": { "x": 0, "y": 0, "z": 0 },
                "w": 10, "d": 10, "h": 28,
                "twistTurns": 0.75
              }
            ],
            "connections": []
          }
        }
      }
    }
  ]
}

""";
    }
}
