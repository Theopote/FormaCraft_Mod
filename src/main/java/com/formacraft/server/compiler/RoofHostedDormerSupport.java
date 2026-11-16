package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Per-emission roof occupancy, used only to extend explicitly hosted dormer walls down to their roof. */
final class RoofHostedDormerSupport {
    private record Key(String slot, String id) {}
    private final Map<Key, Map<BlockPos, Integer>> roofs = new HashMap<>();

    void record(Component roof, List<BlockPatch> patches) {
        if (!ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(roof.componentType()))) return;
        String id = ComponentPlanTypes.getParamString(roof.params(), "component_id");
        if (id == null) return;
        var finalAt = new HashMap<BlockPos, BlockPatch>();
        for (var patch : patches) if (patch != null)
            finalAt.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
        var tops = new HashMap<BlockPos, Integer>();
        for (var patch : finalAt.values()) if (GeneratedSurfaceCapture.occupied(patch))
            tops.merge(new BlockPos(patch.dx(), 0, patch.dz()), patch.dy(), Math::max);
        roofs.put(new Key(ComponentPlanTypes.slotKey(roof), id), tops);
    }

    Optional<CapabilityGap> supportGap(Component dormer, Vec3i slotOffset) {
        if (!ComponentSatelliteAligner.isDormer(dormer)) return Optional.empty();
        String host = resolveDormerRoofHostId(dormer);
        // Unhosted dormers are outside this check; an explicit host that cannot be resolved is a gap.
        if (host == null || host.isBlank()) return Optional.empty();
        Vec3i origin = ComponentPlanCompiler.resolveMassOrigin(dormer);
        Dimensions dims = dormer.dimensions();
        if (origin == null || dims == null) return Optional.empty();
        var roof = roofs.get(new Key(ComponentPlanTypes.slotKey(dormer), host));
        if (roof == null) {
            // host_id often points at the owning MASS; prefer an explicit roof attachment id,
            // else accept any recorded roof in the same slot (mansard dormers bind late).
            roof = findRoofForMassHost(dormer, host);
        }
        if (roof == null) {
            return Optional.of(new CapabilityGap(
                    "E_DORMER_ROOF_SUPPORT",
                    "老虎窗声明的宿主屋顶不存在：id=" + ComponentPlanTypes.paramIdentity(dormer)
                            + " host=" + host + " slot=" + ComponentPlanTypes.slotKey(dormer),
                    "components[]",
                    List.of("将 host_id 指向同 slot 下真实存在的 ROOF component_id，或先生成该屋面。")));
        }
        int ox = slotOffset == null ? 0 : slotOffset.x();
        int oz = slotOffset == null ? 0 : slotOffset.z();
        for (int x = 0; x < dims.width(); x++) for (int z = 0; z < dims.depth(); z++) {
            var column = new BlockPos(origin.x() + ox + x, 0, origin.z() + oz + z);
            if (!roof.containsKey(column)) return Optional.of(new CapabilityGap(
                    "E_DORMER_ROOF_SUPPORT",
                    "老虎窗缺少宿主屋面支撑：id=" + ComponentPlanTypes.paramIdentity(dormer)
                            + " host=" + host + " plan x=" + column.getX() + " z=" + column.getZ(),
                    "components[]", List.of("将老虎窗移到宿主实际屋面覆盖范围内，或补齐对应屋面；不要保留悬空窗体。")));
        }
        return Optional.empty();
    }

    /** Prefer explicit roof attachment ids over mass host_id. */
    private static String resolveDormerRoofHostId(Component dormer) {
        String roofHost = ComponentPlanTypes.getParamString(
                dormer.params(), "attachment_host_id", "attachmentHostId", "roof_host_id", "roofHostId");
        if (roofHost != null && !roofHost.isBlank()) return roofHost;
        return ComponentPlanTypes.getParamString(dormer.params(), "host_id", "hostId");
    }

    private Map<BlockPos, Integer> findRoofForMassHost(Component dormer, String massHost) {
        if (dormer == null || massHost == null || massHost.isBlank()) return null;
        // Only remap mass-like hosts (mass_main / shaft / tier1_body). A wrong or missing roof
        // id such as "missing" / "roof" must stay a hard gap — do not steal another roof.
        String lower = massHost.toLowerCase(Locale.ROOT);
        boolean massLike = lower.contains("mass") || lower.contains("body") || lower.contains("cella")
                || lower.contains("hall") || lower.contains("shaft") || lower.contains("tier")
                || lower.contains("tower") || lower.contains("keep") || lower.contains("wing")
                || lower.startsWith("main") || lower.endsWith("_main");
        if (!massLike) return null;
        String slot = ComponentPlanTypes.slotKey(dormer);
        Map<BlockPos, Integer> best = null;
        for (var entry : roofs.entrySet()) {
            if (!slot.equals(entry.getKey().slot())) continue;
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                if (best == null || entry.getValue().size() > best.size()) {
                    best = entry.getValue();
                }
            }
        }
        return best;
    }

    Component extendToRoof(Component dormer, Vec3i slotOffset) {
        if (!ComponentSatelliteAligner.isDormer(dormer)) return dormer;
        String host = resolveDormerRoofHostId(dormer);
        var roof = roofs.get(new Key(ComponentPlanTypes.slotKey(dormer), host));
        if (roof == null) roof = findRoofForMassHost(dormer, host);
        Vec3i origin = ComponentPlanCompiler.resolveMassOrigin(dormer);
        Dimensions dims = dormer.dimensions();
        if (roof == null || origin == null || dims == null) return dormer;
        int ox = slotOffset == null ? 0 : slotOffset.x();
        int oy = slotOffset == null ? 0 : slotOffset.y();
        int oz = slotOffset == null ? 0 : slotOffset.z();
        int lowestSurface = Integer.MAX_VALUE;
        for (int x = 0; x < dims.width(); x++) for (int z = 0; z < dims.depth(); z++) {
            Integer y = roof.get(new BlockPos(origin.x() + ox + x, 0, origin.z() + oz + z));
            // An incomplete footprint cannot justify adding a wall hanging over a roof hole.
            if (y == null) return dormer;
            lowestSurface = Math.min(lowestSurface, y);
        }
        int bottom = lowestSurface + 1 - oy;
        if (bottom >= origin.y()) return dormer;
        int top = origin.y() + dims.height();
        var params = new HashMap<String, Object>();
        if (dormer.params() != null) params.putAll(dormer.params());
        params.put("anchor_mode", "min_corner");
        return new Component(dormer.componentType(), dormer.slotId(), new Vec3i(origin.x(), bottom, origin.z()),
                new Dimensions(dims.width(), dims.depth(), top - bottom), dormer.features(), params);
    }
}
