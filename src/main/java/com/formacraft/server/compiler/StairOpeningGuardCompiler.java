package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import java.util.*;
/** Expand explicit stair-opening guards along the sides, keeping the upper arrival open. */
final class StairOpeningGuardCompiler {
    static void apply(List<Component> parts) {
        var extras=new ArrayList<Component>();
        var handled= new HashSet<>();
        for(var it=parts.listIterator();it.hasNext();) {
            var rail=it.next();
            if(rail==null || rail.params()==null || rail.relativePosition()==null
                    || rail.params().get("host_id")==null
                    || !isRailSeed(rail)) continue;
            // Explicit stair_opening placement wins; otherwise a thin rail strip next to an
            // interior flight is treated as the authored guard seed (adobe / townhouse plans).
            boolean explicitGuard = "stair_opening".equals(rail.params().get("placement"))
                    || hasFeature(rail, "stair_opening_guard");
            boolean thinSeed = !explicitGuard
                    && rail.dimensions()!=null
                    && Math.min(rail.dimensions().width(), rail.dimensions().depth()) <= 2
                    && Math.max(rail.dimensions().width(), rail.dimensions().depth()) <= 6;
            if(!explicitGuard && !thinSeed) continue;
            for(var stair:parts) {
                if(stair==null || stair.params()==null || stair.relativePosition()==null
                        || !Objects.equals(stair.slotId(),rail.slotId())
                        || !Objects.equals(stair.params().get("host_id"),rail.params().get("host_id"))
                        || Boolean.TRUE.equals(stair.params().get("exterior"))
                        || !com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(stair)
                        || !(stair.params().get("from") instanceof Map<?,?> a)
                        || !(stair.params().get("to") instanceof Map<?,?> b)) continue;
                int ax=n(a,"x"),az=n(a,"z"),bx=n(b,"x"),bz=n(b,"z");
                if(ax!=bx || az==bz) continue; // Z-axis straight flights; other forms retain their authored guard.
                int width=stair.params().get("width") instanceof Number v?v.intValue():3;
                var r=stair.relativePosition();
                int left=r.x()+ax-width/2-1,right=left+width+1;
                int z0=r.z()+Math.min(az,bz),z1=r.z()+Math.max(az,bz);
                if(!handled.add(rail.params().get("host_id"))) {it.remove();break;}
                // Stand on the arrival floor plane (never embed in the slab at arrival Y).
                int arrival = r.y() + Math.max(n(a, "y"), n(b, "y"));
                int y = Math.max(rail.relativePosition().y(), arrival + 1);
                extras.add(line(rail,"left",left,y,z0,1,z1-z0+1));
                extras.add(line(rail,"right",right,y,z0,1,z1-z0+1));
                int lowZ=n(a,"y")<n(b,"y")?az:bz;
                int highZ=lowZ==az?bz:az;
                int cap=r.z()+lowZ+Integer.signum(lowZ-highZ);
                extras.add(line(rail,"end",left+1,y,cap,width,1));
                it.remove(); break;
            }
        }
        parts.addAll(extras);
    }
    private static Component line(Component source,String suffix,int x,int y,int z,int w,int d) {
        var p= new HashMap<>(source.params());
        p.put("placement","compiled_stair_guard");p.put("anchor_mode","min_corner");
        p.put("railing", true);
        p.put("role", "railing");
        normalizeFenceMaterial(p);
        p.put("component_id", p.get("component_id") +"_"+suffix);
        return new Component("DECOR_DETAIL",source.slotId(),new Vec3i(x,y,z),new Dimensions(w,d,1),List.of(),p);
    }

    /** Prefer an explicit fence block; map bare "wood"/palette tokens away from planks. */
    private static void normalizeFenceMaterial(Map<String, Object> p) {
        String block = p.get("block") == null ? null : String.valueOf(p.get("block"));
        String material = p.get("material") == null ? null : String.valueOf(p.get("material"));
        if (block != null && block.contains("fence")) {
            p.put("material", block.contains(":") ? block : "minecraft:" + block);
            p.put("block", block.contains(":") ? block : "minecraft:" + block);
            return;
        }
        if (material != null && material.contains("fence")) {
            String fence = material.contains(":") ? material : "minecraft:" + material;
            p.put("material", fence);
            p.put("block", fence);
            return;
        }
        String fence = "minecraft:oak_fence";
        if (material != null) {
            String lower = material.toLowerCase(Locale.ROOT);
            if (lower.contains("dark_oak")) fence = "minecraft:dark_oak_fence";
            else if (lower.contains("spruce")) fence = "minecraft:spruce_fence";
            else if (lower.contains("birch")) fence = "minecraft:birch_fence";
            else if (lower.contains("jungle")) fence = "minecraft:jungle_fence";
            else if (lower.contains("acacia")) fence = "minecraft:acacia_fence";
            else if (lower.contains("mangrove")) fence = "minecraft:mangrove_fence";
            else if (lower.contains("cherry")) fence = "minecraft:cherry_fence";
            else if (lower.contains("bamboo")) fence = "minecraft:bamboo_fence";
            else if (lower.contains("crimson")) fence = "minecraft:crimson_fence";
            else if (lower.contains("warped")) fence = "minecraft:warped_fence";
        }
        p.put("material", fence);
        p.put("block", fence);
    }

    private static boolean isRailSeed(Component rail) {
        // Type gate first: enriched ENTRANCE features often include plan tokens like
        // "second_floor_stair_opening_railing", which must never beat a real rail seed.
        String type = rail.componentType() == null ? "" : rail.componentType().trim().toUpperCase(Locale.ROOT);
        if ("ENTRANCE".equals(type) || "DOOR".equals(type) || "PORTAL".equals(type)
                || "FACADE_WINDOWS".equals(type) || "WINDOW".equals(type)
                || "PRIMITIVE".equals(type) || "MASS".equals(type) || "MASS_MAIN".equals(type)
                || "TOWER".equals(type) || "STRUCTURE".equals(type)
                || "STAIR_SYSTEM".equals(type) || "STAIRCASE".equals(type) || "STAIRS".equals(type)
                || "ROOF".equals(type) || "FOUNDATION".equals(type)) {
            return false;
        }
        if (Boolean.TRUE.equals(rail.params().get("railing")) || "railing".equals(rail.params().get("role"))) {
            return true;
        }
        if (hasFeature(rail, "railing") || hasFeature(rail, "stair_opening_guard")) {
            return true;
        }
        Object id = rail.params().get("component_id");
        if (id == null) return false;
        String lower = String.valueOf(id).toLowerCase(Locale.ROOT);
        return lower.contains("rail") || lower.contains("parapet") || lower.contains("护栏")
                || lower.contains("guardrail") || lower.contains("guard_rail")
                || lower.contains("opening_guard") || lower.contains("stair_guard");
    }

    private static boolean hasFeature(Component c, String token) {
        if (c.features() == null || token == null) return false;
        for (String f : c.features()) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int n(Map<?,?> p,String k) {return p.get(k) instanceof Number n?n.intValue():0;}
}
