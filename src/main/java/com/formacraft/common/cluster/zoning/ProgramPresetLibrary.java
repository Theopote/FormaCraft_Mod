package com.formacraft.common.cluster.zoning;

import com.formacraft.common.semantic.ComponentPreset;
import com.formacraft.common.semantic.SemanticComponentType;

import java.util.EnumMap;
import java.util.Map;

/**
 * ProgramPresetLibrary（程序预设库）
 * <p>
 * K3.1 核心：Program → Preset 映射库
 * <p>
 * 提供"通用城市街区"默认库，一接上就能跑
 */
public final class ProgramPresetLibrary {

    private static final Map<BuildingProgram, ComponentPreset> DEFAULT = new EnumMap<>(BuildingProgram.class);

    static {
        // COMMERCIAL：商业街
        DEFAULT.put(BuildingProgram.COMMERCIAL, new ComponentPreset(
                "PRESET_COMMERCIAL_STREET",
                "Commercial street frontage: prioritize lively facade, signage, lighting, small street furniture."
        ).add(new ComponentPreset.Item(SemanticComponentType.MASS_MAIN,        1.00f, 0.00f, 6, 12, "align to street, shallow depth ok"))
         .add(new ComponentPreset.Item(SemanticComponentType.ENTRANCE,         0.90f, 0.00f, 2,  4,  "street-facing entrance, canopy allowed"))
         .add(new ComponentPreset.Item(SemanticComponentType.FACADE_WINDOWS,   0.95f, 0.00f, 2,  8,  "large shop windows on street side"))
         .add(new ComponentPreset.Item(SemanticComponentType.SIGNAGE,          0.85f, 0.00f, 2,  6,  "signboard or lightbox centered above entrance"))
         .add(new ComponentPreset.Item(SemanticComponentType.STREET_LIGHTS,    0.70f, 0.25f, 1,  2,  "place along path every ~10-14 blocks"))
         .add(new ComponentPreset.Item(SemanticComponentType.STREET_FURNITURE, 0.50f, 0.20f, 1,  3,  "stalls, bins, banners; keep walk clearance"))
        );

        // RESIDENTIAL：住宅
        DEFAULT.put(BuildingProgram.RESIDENTIAL, new ComponentPreset(
                "PRESET_RESIDENTIAL_ROW",
                "Residential: calmer facade rhythm, boundary wall, small garden; less signage."
        ).add(new ComponentPreset.Item(SemanticComponentType.MASS_MAIN,        1.00f, 0.00f, 6, 12, "rowhouse or small courtyard house"))
         .add(new ComponentPreset.Item(SemanticComponentType.FENCE_OR_WALL,    0.80f, 0.00f, 1,  2,  "low wall or fence at front edge"))
         .add(new ComponentPreset.Item(SemanticComponentType.ENTRANCE,         0.85f, 0.00f, 2,  4,  "porch/stoop; simple"))
         .add(new ComponentPreset.Item(SemanticComponentType.FACADE_WINDOWS,   0.90f, 0.00f, 2,  6,  "smaller windows, consistent spacing"))
         .add(new ComponentPreset.Item(SemanticComponentType.BALCONY,          0.35f, 0.00f, 2,  5,  "optional balconies for 2nd floor"))
         .add(new ComponentPreset.Item(SemanticComponentType.GREENERY,         0.55f, 0.15f, 2,  6,  "small garden patches / planters"))
        );

        // PLAZA：广场
        DEFAULT.put(BuildingProgram.PLAZA, new ComponentPreset(
                "PRESET_PLAZA_NODE",
                "Plaza: open space, paving, seating, greenery, optional focal object."
        ).add(new ComponentPreset.Item(SemanticComponentType.PAVING,       1.00f, 0.00f, 10, 30, "paving fill within polygon/zone"))
         .add(new ComponentPreset.Item(SemanticComponentType.PLAZA_CORE,   0.65f, 0.00f,  3,  9, "fountain/statue/monument at center"))
         .add(new ComponentPreset.Item(SemanticComponentType.GREENERY,     0.75f, 0.20f,  2,  8, "tree pits around edges"))
         .add(new ComponentPreset.Item(SemanticComponentType.BENCHES,      0.70f, 0.25f,  1,  2, "benches along edges, face inward"))
         .add(new ComponentPreset.Item(SemanticComponentType.STREET_LIGHTS,0.60f, 0.20f,  1,  2, "lights at corners"))
        );

        // INDUSTRIAL：工业
        DEFAULT.put(BuildingProgram.INDUSTRIAL, new ComponentPreset(
                "PRESET_INDUSTRIAL_YARD",
                "Industrial: heavier masses, yard/storage, chimneys, simple openings."
        ).add(new ComponentPreset.Item(SemanticComponentType.MASS_MAIN,     1.00f, 0.00f, 10, 20, "warehouse/factory hall"))
         .add(new ComponentPreset.Item(SemanticComponentType.ENTRANCE,      0.70f, 0.00f,  3,  6, "large gate/roller door"))
         .add(new ComponentPreset.Item(SemanticComponentType.CHIMNEY,       0.55f, 0.00f,  2,  5, "1-2 chimneys, height emphasize"))
         .add(new ComponentPreset.Item(SemanticComponentType.YARD_STORAGE,  0.75f, 0.20f,  4, 12, "crates, piles, fenced yard"))
         .add(new ComponentPreset.Item(SemanticComponentType.STREET_LIGHTS, 0.55f, 0.20f,  1,  2, "industrial lamps, sparse"))
        );

        // DEFENSIVE：防御
        DEFAULT.put(BuildingProgram.DEFENSIVE, new ComponentPreset(
                "PRESET_DEFENSIVE_EDGE",
                "Defensive: towers, battlements, rampart walkway."
        ).add(new ComponentPreset.Item(SemanticComponentType.TOWER_NODE,     0.85f, 0.00f, 5,  9,  "at corners or key nodes"))
         .add(new ComponentPreset.Item(SemanticComponentType.BATTLEMENTS,    0.90f, 0.00f, 2,  6,  "parapets along wall top"))
         .add(new ComponentPreset.Item(SemanticComponentType.WALKWAY_RAMPART,0.70f, 0.00f, 2,  5,  "walkable path on wall"))
         .add(new ComponentPreset.Item(SemanticComponentType.GATEWAY,        0.60f, 0.00f, 4, 10,  "if label=gate, strongly include"))
        );

        // MIXED_USE：混合用途
        DEFAULT.put(BuildingProgram.MIXED_USE, new ComponentPreset(
                "PRESET_MIXED_USE",
                "Mixed-use: commercial ground + residential above."
        ).add(new ComponentPreset.Item(SemanticComponentType.MASS_MAIN,       1.00f, 0.00f, 8, 14, "ground floor higher"))
         .add(new ComponentPreset.Item(SemanticComponentType.FACADE_WINDOWS,  0.95f, 0.00f, 2, 10, "shop windows + upper smaller windows"))
         .add(new ComponentPreset.Item(SemanticComponentType.SIGNAGE,         0.60f, 0.00f, 2,  6, "moderate signage"))
         .add(new ComponentPreset.Item(SemanticComponentType.BALCONY,         0.45f, 0.00f, 2,  6, "upper balconies"))
        );

        // PARK：公园
        DEFAULT.put(BuildingProgram.PARK, new ComponentPreset(
                "PRESET_PARK",
                "Park: greenery dominant, paths, benches."
        ).add(new ComponentPreset.Item(SemanticComponentType.GREENERY, 1.00f, 0.40f, 2, 10, "trees, bushes, flowerbeds"))
         .add(new ComponentPreset.Item(SemanticComponentType.PAVING,   0.55f, 0.10f, 2,  8, "small walking paths"))
         .add(new ComponentPreset.Item(SemanticComponentType.BENCHES,  0.60f, 0.20f, 1,  2, "benches along paths"))
        );

        // CIVIC：市政
        DEFAULT.put(BuildingProgram.CIVIC, new ComponentPreset(
                "PRESET_CIVIC",
                "Civic: formal entrance, larger mass, plaza-front."
        ).add(new ComponentPreset.Item(SemanticComponentType.MASS_MAIN,    1.00f, 0.00f, 12, 22, "symmetry preferred"))
         .add(new ComponentPreset.Item(SemanticComponentType.ENTRANCE,     0.95f, 0.00f,  4,  8, "grand entrance"))
         .add(new ComponentPreset.Item(SemanticComponentType.PAVING,       0.60f, 0.00f,  6, 16, "forecourt paving"))
         .add(new ComponentPreset.Item(SemanticComponentType.STREET_LIGHTS,0.55f, 0.15f,  1,  2, "formal lights"))
        );

        // LANDMARK：地标
        DEFAULT.put(BuildingProgram.LANDMARK, new ComponentPreset(
                "PRESET_LANDMARK",
                "Landmark: iconic silhouette, allow taller elements."
        ).add(new ComponentPreset.Item(SemanticComponentType.MASS_MAIN,      1.00f, 0.00f, 10, 18, "taller than neighbors"))
         .add(new ComponentPreset.Item(SemanticComponentType.MASS_SECONDARY, 0.55f, 0.00f,  4, 10, "supporting wings"))
         .add(new ComponentPreset.Item(SemanticComponentType.PLAZA_CORE,     0.40f, 0.00f,  3,  9, "monument base / signage"))
         .add(new ComponentPreset.Item(SemanticComponentType.STREET_LIGHTS,  0.60f, 0.20f,  1,  2, "highlight lighting"))
        );

        // RELIGIOUS：宗教（兜底为 CIVIC）
        DEFAULT.put(BuildingProgram.RELIGIOUS, DEFAULT.get(BuildingProgram.CIVIC));

        // PORT：港口（兜底为 INDUSTRIAL）
        DEFAULT.put(BuildingProgram.PORT, DEFAULT.get(BuildingProgram.INDUSTRIAL));

        // FARM：农田（兜底为 RESIDENTIAL）
        DEFAULT.put(BuildingProgram.FARM, DEFAULT.get(BuildingProgram.RESIDENTIAL));
    }

    private ProgramPresetLibrary() {}

    /**
     * 获取默认预设
     */
    public static ComponentPreset getDefault(BuildingProgram program) {
        ComponentPreset p = DEFAULT.get(program);
        if (p != null) return p;
        // 未定义的全部兜底为住宅
        return DEFAULT.get(BuildingProgram.RESIDENTIAL);
    }
}

