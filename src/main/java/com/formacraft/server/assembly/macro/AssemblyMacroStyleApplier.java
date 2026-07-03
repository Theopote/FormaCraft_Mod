package com.formacraft.server.assembly.macro;

import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AssemblyMacroStyleApplier {
    private AssemblyMacroStyleApplier() {}

    @SuppressWarnings("unchecked")
    static void apply(Map<String, Object> root,
                      Map<String, Object> graph,
                      Object compsObj,
                      Object connsObj,
                      Map<String, Object> primary,
                      Map<String, Object> macro,
                      List<AssemblyValidationIssue> issues) {
        Object styleObj = macro.get("style");
        if (styleObj == null) styleObj = macro.get("culture");
        if (styleObj == null) return;

        Map<String, Object> style = null;
        if (styleObj instanceof Map<?, ?> mm) style = AssemblyMacroSupport.safeMap(mm);
        if (style == null) {
            issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_TYPE", "macro.style/culture 建议是对象（map）"));
            return;
        }

        String styleId = AssemblyMacroSupport.str(style.get("styleId"), AssemblyMacroSupport.str(style.get("style_id"), AssemblyMacroSupport.str(style.get("id"), null)));
        if (styleId != null) styleId = styleId.trim();
        String intent = AssemblyMacroSupport.str(style.get("intent"), AssemblyMacroSupport.str(style.get("mood"), null));
        if (intent != null) intent = intent.trim();
        String entranceFace = AssemblyMacroSupport.str(style.get("entranceFace"),
                AssemblyMacroSupport.str(style.get("entranceFacing"), AssemblyMacroSupport.str(root.get("entranceFacing"), null)));
        if (entranceFace != null) entranceFace = entranceFace.trim().toUpperCase(Locale.ROOT);

        double density = AssemblyMacroSupport.clamp01(AssemblyMacroSupport.d(style.get("density"), 0.6));
        double symmetry = AssemblyMacroSupport.clamp01(AssemblyMacroSupport.d(style.get("symmetry"), 0.6));
        double verticality = AssemblyMacroSupport.clamp01(AssemblyMacroSupport.d(style.get("verticality"), 0.6));
        double transparency = AssemblyMacroSupport.clamp01(AssemblyMacroSupport.d(style.get("transparency"), 0.4));
        double structureExposure = AssemblyMacroSupport.clamp01(AssemblyMacroSupport.d(style.get("structureExposure"), AssemblyMacroSupport.d(style.get("structure_exposure"), 0.5)));

        // Palette mapping (best-effort, only if paletteId is absent)
        if (root.get("paletteId") == null) {
            String sid = (styleId != null) ? styleId.toUpperCase(Locale.ROOT) : "";
            String it = (intent != null) ? intent.toUpperCase(Locale.ROOT) : "";
            String pal = null;
            if (sid.contains("GOTHIC") || it.contains("神圣") || it.contains("SACRED")) pal = "PALETTE_GOTHIC_CATHEDRAL_A";
            else if (sid.contains("INDUSTRIAL") || it.contains("工业") || it.contains("INDUSTRIAL")) pal = "PALETTE_INDUSTRIAL_STEEL_A";
            if (pal != null) {
                root.put("paletteId", pal);
                issues.add(AssemblyMacroSupport.warn("$.macro.style.paletteId", "W_MACRO_STYLE_PALETTE", "style macro set paletteId=" + pal));
            }
        }

        if (primary == null) {
            issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_NO_PRIMARY", "style macro: 没有可作用的主组件（components 为空或 primaryComponent 未找到）"));
            return;
        }
        String pType = AssemblyMacroSupport.str(primary.get("type"), AssemblyMacroSupport.str(primary.get("op"), "")).trim().toUpperCase(Locale.ROOT);
        if (!(pType.contains("SHELL_BOX") || pType.contains("BOX_SHELL"))) {
            issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_PRIMARY_UNSUPPORTED", "style macro 当前仅对 SHELL_BOX 主组件做注入（当前=" + pType + "）"));
            return;
        }

        Integer w = AssemblyMacroSupport.intOrNull(primary.get("w"));
        Integer dep = AssemblyMacroSupport.intOrNull(primary.get("d"));
        Integer h = AssemblyMacroSupport.intOrNull(primary.get("h"));
        if (w == null || dep == null || h == null) {
            issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_PRIMARY_DIM", "style macro 需要主组件含 w/d/h"));
            return;
        }

        Map<String, Object> facade = null;
        Object fObj = primary.get("facade");
        if (fObj instanceof Map<?, ?> fm) facade = AssemblyMacroSupport.safeMap(fm);
        if (facade == null) {
            facade = new java.util.LinkedHashMap<>();
            primary.put("facade", facade);
        }

        // Which style bucket?
        String sid = (styleId != null) ? styleId.toUpperCase(Locale.ROOT) : "";
        String it = (intent != null) ? intent.toUpperCase(Locale.ROOT) : "";
        boolean gothic = sid.contains("GOTHIC") || it.contains("神圣") || it.contains("SACRED");
        boolean industrial = sid.contains("INDUSTRIAL") || it.contains("工业") || it.contains("INDUSTRIAL");
        boolean chinese = sid.contains("CHINESE") || sid.contains("ASIAN")
                || sid.contains("HUI") || sid.contains("HUIZHOU") || sid.contains("JIANGNAN")
                || it.contains("中式") || it.contains("传统") || it.contains("CHINESE") || it.contains("TRADITIONAL");

        // 1) Gothic: pointed arches + rose window + vertical rhythm + (optional) buttresses
        if (gothic) {
            if (facade.get("openings") == null && facade.get("opening") == null) {
                int cols = AssemblyMacroSupport.clampInt((int) Math.round(3 + density * 6), 2, 12);
                int rows = AssemblyMacroSupport.clampInt((int) Math.round(1 + density * 2), 1, 4);
                int winH = AssemblyMacroSupport.clampInt((int) Math.round(6 + verticality * 10), 5, Math.max(6, h - 4));
                int sillY = AssemblyMacroSupport.clampInt((int) Math.round(3 + (1.0 - verticality) * 3), 1, Math.max(1, h - winH - 2));
                List<Map<String, Object>> openings = new ArrayList<>();
                openings.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "face", "NORTH,SOUTH",
                        "kind", "ARCH_WINDOW",
                        "archType", "POINTED",
                        "cols", cols,
                        "rows", rows,
                        "winW", 2,
                        "winH", winH,
                        "sillY", sillY
                )));
                openings.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "face", "WEST,EAST",
                        "kind", "ROSE_WINDOW",
                        "r", AssemblyMacroSupport.clampInt(Math.max(4, Math.min(w, dep) / 3), 4, 10),
                        "centerY", AssemblyMacroSupport.clampInt(h - 6, 6, h - 2),
                        "petals", AssemblyMacroSupport.clampInt((int) Math.round(6 + density * 8), 5, 16)
                )));
                facade.put("openings", openings);
                issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_GOTHIC_OPENINGS", "style=gothic: injected facade.openings (ARCH_WINDOW + ROSE_WINDOW)"));
            }

            if (facade.get("surfaceBands") == null && facade.get("SURFACE_BANDS") == null && facade.get("bands") == null) {
                int step = AssemblyMacroSupport.clampInt((int) Math.round(6 - density * 3), 2, 8);
                Map<String, Object> sb = new java.util.LinkedHashMap<>();
                sb.put("verticalBands", java.util.List.of(java.util.Map.of(
                        "step", step,
                        "thickness", 1,
                        "outset", AssemblyMacroSupport.clampInt((int) Math.round(structureExposure * 2), 0, 2),
                        "material", "minecraft:stone_brick_wall"
                )));
                facade.put("surfaceBands", sb);
                issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_GOTHIC_BANDS", "style=gothic: injected facade.surfaceBands vertical rhythm"));
            }

            // Optional buttresses if structure exposure is high.
            if (structureExposure > 0.55) {
                Map<String, Object> g = graph;
                if (g == null) {
                    g = new java.util.LinkedHashMap<>();
                    root.put("graph", g);
                }
                Object co = g.get("components");
                List<Object> comps;
                if (co instanceof List<?> list) comps = (List<Object>) list;
                else { comps = new ArrayList<>(); g.put("components", comps); }

                java.util.HashSet<String> used = new java.util.HashSet<>();
                for (Object it0 : comps) if (it0 instanceof Map<?, ?> cm) {
                    String id0 = AssemblyMacroSupport.str(cm.get("id"), "").trim();
                    if (!id0.isEmpty()) used.add(id0);
                }

                int hx = w / 2;
                int hz = dep / 2;
                int fromY = AssemblyMacroSupport.clampInt(h - 4, 6, h);
                int toY = AssemblyMacroSupport.clampInt(h - 8, 4, h);
                int pierDown = AssemblyMacroSupport.clampInt((int) Math.round(6 + verticality * 10), 4, 24);
                String idL = AssemblyMacroSupport.uniqueId("ButtressL", used); used.add(idL);
                String idR = AssemblyMacroSupport.uniqueId("ButtressR", used); used.add(idR);
                comps.add(java.util.Map.of(
                        "id", idL,
                        "type", "BUTTRESS",
                        "from", java.util.Map.of("x", -hx, "y", fromY, "z", 0),
                        "to", java.util.Map.of("x", -hx - 4, "y", toY, "z", 0),
                        "rise", AssemblyMacroSupport.clampInt((int) Math.round(3 + verticality * 5), 2, 12),
                        "pierDown", pierDown
                ));
                comps.add(java.util.Map.of(
                        "id", idR,
                        "type", "BUTTRESS",
                        "from", java.util.Map.of("x", hx, "y", fromY, "z", 0),
                        "to", java.util.Map.of("x", hx + 4, "y", toY, "z", 0),
                        "rise", AssemblyMacroSupport.clampInt((int) Math.round(3 + verticality * 5), 2, 12),
                        "pierDown", pierDown
                ));
                issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_GOTHIC_BUTTRESS", "style=gothic: injected BUTTRESS components"));
            }
        }

        // 2) Chinese/Traditional: decorative elements (dougong, lattice windows, roof finials)
        if (chinese) {
            Object existingDeco = facade.get("decorativeElements");
            if (existingDeco == null) {
                List<Map<String, Object>> decorativeElements = new ArrayList<>();

                if (density >= 0.3) {
                    decorativeElements.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "type", "CONNECTOR",
                        "assetId", "chinese_dougong_small",
                        "placement", "COLUMN_TOP",
                        "face", "ALL",
                        "density", Math.max(0.5, density)
                    )));
                }

                if (density >= 0.2) {
                    String[] latticeVariants = {
                        "chinese_lattice_window_cross",
                        "chinese_lattice_window_diamond",
                        "chinese_lattice_window_square"
                    };
                    int variantIndex = (w + dep + h) % latticeVariants.length;
                    decorativeElements.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "type", "FILLER",
                        "assetId", latticeVariants[variantIndex],
                        "placement", "WINDOW_FRAME",
                        "face", "ALL"
                    )));
                }

                if (structureExposure >= 0.6) {
                    String doorFace = (entranceFace == null || entranceFace.isBlank()) ? "NORTH" : entranceFace;
                    decorativeElements.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "type", "FILLER",
                        "assetId", "chinese_door_luxury",
                        "placement", "ENTRANCE",
                        "face", doorFace
                    )));
                }

                if (structureExposure >= 0.5) {
                    decorativeElements.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "type", "TERMINATOR",
                        "assetId", "chinese_roof_finial",
                        "placement", "ROOF_RIDGE_END",
                        "face", "ALL"
                    )));
                }

                if (structureExposure >= 0.6) {
                    decorativeElements.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "type", "CONNECTOR",
                        "assetId", "chinese_flying_eave",
                        "placement", "ROOF_EDGE",
                        "face", "ALL"
                    )));
                }

                if (structureExposure >= 0.4) {
                    decorativeElements.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "type", "TERMINATOR",
                        "assetId", "chinese_eave_detail",
                        "placement", "ROOF_EDGE",
                        "face", "ALL"
                    )));
                }

                if (!decorativeElements.isEmpty()) {
                    facade.put("decorativeElements", decorativeElements);
                    issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_CHINESE_DECOR", "style=chinese/traditional: injected facade.decorativeElements (dougong, lattice windows, roof finials)"));
                }
            }
        }

        // 3) Industrial: exoskeleton frame grid around primary
        if (industrial) {
            Map<String, Object> g = graph;
            if (g == null) {
                g = new java.util.LinkedHashMap<>();
                root.put("graph", g);
            }
            Object co = g.get("components");
            List<Object> comps;
            if (co instanceof List<?> list) comps = (List<Object>) list;
            else { comps = new ArrayList<>(); g.put("components", comps); }

            boolean hasFrame = false;
            for (Object it0 : comps) {
                if (it0 instanceof Map<?, ?> cm) {
                    String t = AssemblyMacroSupport.str(cm.get("type"), AssemblyMacroSupport.str(cm.get("op"), "")).trim().toUpperCase(Locale.ROOT);
                    if (t.contains("FRAME_GRID_3D")) { hasFrame = true; break; }
                }
            }
            if (!hasFrame && structureExposure > 0.25) {
                int exW = w + 4;
                int exD = dep + 4;
                int exH = h + 4;
                int step = AssemblyMacroSupport.clampInt((int) Math.round(6 - density * 4), 2, 8);
                String diag = (structureExposure > 0.75) ? "SPACE" : "FACE";
                Map<String, Object> frame = new java.util.LinkedHashMap<>();
                frame.put("id", "Exoskeleton");
                frame.put("type", "FRAME_GRID_3D");
                frame.put("w", exW);
                frame.put("d", exD);
                frame.put("h", exH);
                frame.put("stepX", step);
                frame.put("stepY", step);
                frame.put("stepZ", step);
                frame.put("mode", "SURFACE");
                frame.put("diagonal", diag);
                comps.add(frame);
                issues.add(AssemblyMacroSupport.warn("$.macro.style", "W_MACRO_STYLE_INDUSTRIAL_FRAME", "style=industrial: injected FRAME_GRID_3D exoskeleton (diag=" + diag + ")"));
            }
        }

        // Symmetry slider (numeric) -> routingStyle defaults if not explicitly set.
        if (connsObj instanceof List<?> conns) {
            for (Object it0 : conns) {
                if (!(it0 instanceof Map<?, ?> cm)) continue;
                Map<String, Object> c = AssemblyMacroSupport.safeMap(cm);
                if (c == null) continue;
                if (c.get("routingStyle") != null) continue;
                String rs = (symmetry >= 0.5) ? "PLANNED" : "ORGANIC";
                c.put("routingStyle", rs);
            }
        }
    }
}
