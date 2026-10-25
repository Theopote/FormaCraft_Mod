package com.formacraft.server.edit;

import com.formacraft.common.component.semantic.ShapePreservingMaterialSwap;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Java-owned roof material replace: preserves geometry and BlockState properties.
 * Does not invent global patches from the LLM.
 */
public final class RoofMaterialReplaceCompiler {
    public record CompileResult(List<BlockPatch> patches, List<String> refusals) {
        public CompileResult {
            patches = patches == null ? List.of() : List.copyOf(patches);
            refusals = refusals == null ? List.of() : List.copyOf(refusals);
        }

        public boolean ok() {
            return refusals.isEmpty() && !patches.isEmpty();
        }
    }

    private RoofMaterialReplaceCompiler() {}

    public static CompileResult compile(
            BlockPos origin,
            BuildingPartIndex.RoofPart roof,
            String materialIntentOrBlockId,
            Map<BlockPos, String> liveWorldStates
    ) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(roof, "roof");
        List<String> refusals = new ArrayList<>();
        if (roof.isEmpty()) {
            refusals.add("无法安全定位屋顶：缺少已登记的屋顶方块归属。");
            return new CompileResult(List.of(), refusals);
        }
        Optional<String> material = RoofMaterialIntent.resolveBlockId(materialIntentOrBlockId);
        if (material.isEmpty()) {
            refusals.add("无法解析屋顶材质意图: " + materialIntentOrBlockId);
            return new CompileResult(List.of(), refusals);
        }
        BlockState requested = BlockPatchTargetResolver.parse(material.get());
        if (requested == null) {
            refusals.add("目标材质方块无效: " + material.get());
            return new CompileResult(List.of(), refusals);
        }

        List<BlockPatch> patches = new ArrayList<>();
        for (BlockPos world : roof.cells()) {
            String current = liveWorldStates != null ? liveWorldStates.get(world) : null;
            if (current == null) current = roof.recordedStates().get(world);
            if (current == null || current.isBlank()) {
                refusals.add("缺少屋顶方块当前状态: " + world.toShortString());
                continue;
            }
            BlockState original = BlockPatchTargetResolver.parse(current);
            if (original == null || original.isAir()) {
                refusals.add("屋顶方块状态不可解析: " + world.toShortString() + " " + current);
                continue;
            }
            BlockState swapped = ShapePreservingMaterialSwap.apply(current, requested);
            if (swapped == null || swapped.isAir()) {
                refusals.add("材质映射失败: " + world.toShortString());
                continue;
            }
            if (swapped.equals(original)) {
                if (alreadyDesiredFamily(original, material.get())) {
                    continue; // already correct material family
                }
                refusals.add("目标材质与屋顶形状不兼容: " + world.toShortString()
                        + " from=" + current + " to=" + material.get());
                continue;
            }
            String target = BlockStateStringUtil.fromState(swapped);
            patches.add(new BlockPatch(
                    BlockPatch.REPLACE,
                    world.getX() - origin.getX(),
                    world.getY() - origin.getY(),
                    world.getZ() - origin.getZ(),
                    target));
        }

        if (!refusals.isEmpty()) {
            return new CompileResult(List.of(), refusals);
        }
        if (patches.isEmpty()) {
            return new CompileResult(List.of(), List.of("屋顶材质已是目标材质，无需修改。"));
        }
        return new CompileResult(patches, List.of());
    }

    static boolean alreadyDesiredFamily(BlockState original, String materialBlockId) {
        String path = Registries.BLOCK.getId(original.getBlock()).getPath().toLowerCase(Locale.ROOT);
        String mat = materialBlockId.toLowerCase(Locale.ROOT);
        if (mat.contains(":")) mat = mat.substring(mat.indexOf(':') + 1);
        String stem = mat
                .replaceFirst("_(planks|stairs|slab|wall|fence|bricks|brick|tiles|tile)$", "")
                .replaceFirst("bricks$", "brick");
        if (stem.isBlank()) return false;
        return path.equals(mat) || path.equals(stem) || path.startsWith(stem + "_");
    }
}
