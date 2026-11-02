package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tibetan / Himalayan stupa: square base → anda (bulb) → harmika → chhatravali (相轮) → finial.
 * Distinct from {@link DenseEavesPagodaBuilder}; never emits dense-eave storey loops.
 */
public final class TibetanStupaBuilder {

    public static final String TYPOLOGY_ID = "tibetan_stupa";

    private TibetanStupaBuilder() {}

    public static GeneratedStructure fromBuildingSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return generate(TypologyParamResolver.fromBuildingSpec(spec, TYPOLOGY_ID), origin, world, spec);
    }

    public static GeneratedStructure generate(
            Map<String, Object> params,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        TypologyParams p = new TypologyParams(params);
        int baseW = p.intVal("baseWidth", 9, 5, 21);
        if (baseW % 2 == 0) baseW += 1;
        int height = p.intVal("height", "towerHeight", Math.max(18, baseW * 3), 14, 64);
        int chhatraCount = p.intVal("chhatra_count", "chhatraCount", 5, 3, 9);

        BlockState body = blockOrDefault(world, p.strVal("bodyBlock", p.strVal("wall_block", "minecraft:white_concrete")),
                Blocks.WHITE_CONCRETE.getDefaultState());
        BlockState trim = blockOrDefault(world, p.strVal("trimBlock", "minecraft:smooth_quartz"),
                Blocks.SMOOTH_QUARTZ.getDefaultState());
        BlockState accent = blockOrDefault(world, p.strVal("accentBlock", "minecraft:gold_block"),
                Blocks.GOLD_BLOCK.getDefaultState());
        BlockState spire = blockOrDefault(world, p.strVal("spireBlock", "minecraft:lightning_rod"),
                Blocks.LIGHTNING_ROD.getDefaultState());

        List<PlannedBlock> blocks = new ArrayList<>(baseW * baseW * height);
        int half = baseW / 2;
        var ref = new Object() {
            int y = 0;
        };

        // Stepped square base (基座) — 2–3 terraces.
        int baseTiers = Math.max(2, Math.min(3, half));
        for (int t = 0; t < baseTiers; t++) {
            int h = half - t;
            int tierH = 2;
            for (int dy = 0; dy < tierH; dy++) {
                fillSquare(blocks, origin, ref.y + dy, h, t == 0 ? trim : body);
            }
            ref.y += tierH;
        }

        // Anda / dome (覆钵) — stacked shrinking disks.
        int andaH = Math.max(4, height / 3);
        int andaR = Math.max(2, half - 1);
        for (int dy = 0; dy < andaH; dy++) {
            double t = dy / (double) Math.max(1, andaH - 1);
            // Bulb: widen mid, taper top.
            double scale = 0.55 + 0.45 * Math.sin(Math.PI * Math.min(1.0, t * 1.15));
            int r = Math.max(1, (int) Math.round(andaR * scale));
            fillDisk(blocks, origin, ref.y + dy, r, body);
        }
        ref.y += andaH;

        // Harmika (方座)
        int harmika = Math.max(1, andaR / 2);
        for (int dy = 0; dy < 2; dy++) {
            fillSquare(blocks, origin, ref.y + dy, harmika, trim);
        }
        ref.y += 2;

        // Chhatravali / 相轮 — centered rings on a mast.
        for (int i = 0; i < chhatraCount; i++) {
            int r = Math.max(1, harmika - i / 2);
            ringSquare(blocks, origin, ref.y, r, accent);
            blocks.add(new PlannedBlock(origin.add(0, ref.y, 0), trim));
            ref.y += 1;
            blocks.add(new PlannedBlock(origin.add(0, ref.y, 0), trim));
            ref.y += 1;
        }

        // Finial / 塔刹
        blocks.add(new PlannedBlock(origin.add(0, ref.y, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, ref.y + 1, 0), spire));

        boolean hasBase = blocks.stream().anyMatch(b -> b.getPos().getY() == origin.getY());
        boolean hasAnda = blocks.stream().anyMatch(b ->
                b.getPos().getY() > origin.getY() + 2 && b.getPos().getY() < origin.getY() + ref.y);
        boolean finialCentered = blocks.stream().anyMatch(b ->
                b.getPos().getX() == origin.getX()
                        && b.getPos().getZ() == origin.getZ()
                        && b.getPos().getY() >= origin.getY() + ref.y);
        if (!hasBase || !hasAnda || !finialCentered) {
            throw new com.formacraft.common.typology.TypologyGenerationException(
                    TYPOLOGY_ID,
                    "E_STUPA_MORPHOLOGY",
                    "tibetan_stupa missing base/anda/centered finial",
                    List.of(
                            "hasBase=" + hasBase,
                            "hasAnda=" + hasAnda,
                            "finialCentered=" + finialCentered));
        }

        String desc = String.format(
                "tibetan_stupa (base=%d, h=%d, chhatra=%d, blocks=%d)",
                baseW, height, chhatraCount, blocks.size());
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void fillSquare(List<PlannedBlock> blocks, BlockPos origin, int y, int half, BlockState state) {
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                blocks.add(new PlannedBlock(origin.add(x, y, z), state));
            }
        }
    }

    private static void fillDisk(List<PlannedBlock> blocks, BlockPos origin, int y, int r, BlockState state) {
        int r2 = r * r;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z <= r2 + r) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), state));
                }
            }
        }
    }

    private static void ringSquare(List<PlannedBlock> blocks, BlockPos origin, int y, int half, BlockState state) {
        for (int x = -half; x <= half; x++) {
            blocks.add(new PlannedBlock(origin.add(x, y, -half), state));
            blocks.add(new PlannedBlock(origin.add(x, y, half), state));
        }
        for (int z = -half + 1; z <= half - 1; z++) {
            blocks.add(new PlannedBlock(origin.add(-half, y, z), state));
            blocks.add(new PlannedBlock(origin.add(half, y, z), state));
        }
    }

    private static BlockState blockOrDefault(ServerWorld world, String id, BlockState def) {
        if (id == null || id.isBlank()) return def;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return def;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return def;
        }
    }
}
