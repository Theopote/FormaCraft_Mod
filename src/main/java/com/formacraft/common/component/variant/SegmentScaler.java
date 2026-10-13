package com.formacraft.common.component.variant;

import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Integer, slice-based adaptation. Fixed ends retain their original blocks. */
public final class SegmentScaler {
    private SegmentScaler() {}
    public static final int MAX_AXIS_SIZE = 256;
    public static final int MAX_BLOCKS = 262144;

    public static VoxelGrid applyScaling(StructureTemplate template,
            ComponentPrototype.VariantRules.Scaling rules, PersistedComponentVariant.Params.Scale target) {
        VoxelGrid result = new VoxelGrid();
        if (template == null) return result;
        // Copies are essential: material mapping must never mutate the source template.
        for (Voxel v : template.all()) result.add(v.transform(v.x(), v.y(), v.z()));
        if (rules == null || rules.axes == null || target == null) return result;
        for (Voxel.Axis axis : Voxel.Axis.values()) {
            var rule = rules.axes.get(axis.name());
            int size = switch (axis) { case X -> target.x; case Y -> target.y; case Z -> target.z; };
            if (rule == null || size <= 0 || "FIXED".equalsIgnoreCase(rule.type)) continue;
            Mapping map = mapping(result.all(), axis, rule, size);
            if (map.originalSize() == map.targetSize()) continue;
            result = rebuild(result.all(), axis, map);
        }
        return result;
    }

    public record Mapping(int min, int originalSize, int start, int end, int targetSize, String mode) {
        int middle() { return originalSize - start - end; }
        int targetMiddle() { return targetSize - start - end; }

        /**
         * Map a source-axis coordinate to the adapted axis using the same tile-repeat rule as
         * {@link #sourceSliceForDestination(int)}. Middle points land on the first tiled instance.
         */
        public int position(int coordinate) {
            int local = coordinate - min;
            return min + destinationForSourceLocal(local);
        }

        public int anchoredPosition(int coordinate) { return position(coordinate) - position(0); }

        /** Destination index (0-based within target) for a source local index. */
        int destinationForSourceLocal(int local) {
            if (local < start) return local;
            if (local >= originalSize - end) return local + targetSize - originalSize;
            int mid = middle();
            int tMid = targetMiddle();
            if (mid <= 0 || tMid <= 0) return start;
            int offset = local - start;
            if (offset >= tMid) offset = Math.floorMod(offset, tMid);
            return start + offset;
        }

        /** Source slice index for a destination index within the target size. */
        int sourceSliceForDestination(int destination) {
            if (destination < start) return destination;
            if (destination >= targetSize - end) return destination + originalSize - targetSize;
            int mid = middle();
            if (mid <= 0) return start;
            return start + (destination - start) % mid;
        }
    }

    /** The same coordinate map is used for geometry, attachment marks and socket origins. */
    public static Mapping mapping(List<Voxel> source, Voxel.Axis axis,
            ComponentPrototype.VariantRules.Scaling.AxisRule rule, int targetSize) {
        int min = source.stream().mapToInt(v -> coord(v, axis)).min().orElse(0);
        int max = source.stream().mapToInt(v -> coord(v, axis)).max().orElse(min);
        int original = max - min + 1;
        if (targetSize <= 0 || rule == null || "FIXED".equalsIgnoreCase(rule.type))
            return new Mapping(min, original, 0, 0, original, "FIXED");
        String mode = rule.type == null ? "FIXED" : rule.type.toUpperCase(Locale.ROOT);
        if (!mode.equals("REPEAT") && !mode.equals("TRIM"))
            throw new IllegalArgumentException("Unsupported adaptation mode: " + mode);
        int start = rule.fixedStart != null ? rule.fixedStart : 1;
        int end = rule.fixedEnd != null ? rule.fixedEnd : 1;
        // Respect explicitly tagged middle bands in existing structure templates.
        if (rule.fixedStart == null && rule.fixedEnd == null) {
            String tag = rule.segment != null ? rule.segment : "SEG_MID_" + axis;
            var tagged = source.stream().filter(v -> v.hasSegmentTag(tag)).toList();
            if (!tagged.isEmpty()) {
                start = tagged.stream().mapToInt(v -> coord(v, axis)).min().orElse(min) - min;
                end = max - tagged.stream().mapToInt(v -> coord(v, axis)).max().orElse(max);
            }
        }
        if (targetSize == original) return new Mapping(min, original, start, end, original, mode);
        if (targetSize > MAX_AXIS_SIZE || targetSize < 1)
            throw new IllegalArgumentException("Target dimension must be between 1 and " + MAX_AXIS_SIZE);
        if (rule.min != null && targetSize < rule.min || rule.max != null && targetSize > rule.max)
            throw new IllegalArgumentException("Target dimension is outside the author's allowed range");
        if (start < 0 || end < 0 || start + end >= original || targetSize <= start + end)
            throw new IllegalArgumentException("Not enough room for fixed ends and a middle slice");
        if (mode.equals("TRIM") && targetSize > original)
            throw new IllegalArgumentException("TRIM permits shortening only");
        String paired = middlePairedBlockMessage(source, axis, min, original, start, end);
        if (paired != null) throw new IllegalArgumentException(paired);
        return new Mapping(min, original, start, end, targetSize, mode);
    }

    /** Shared paired-block scan for runtime mapping and capture-time validation. */
    public static boolean isPairedBlock(String state) {
        if (state == null) return false;
        return state.contains("half=upper") || state.contains("half=lower")
                || state.contains("part=head") || state.contains("part=foot");
    }

    /**
     * @return human-readable error, or null if the middle band is clean
     */
    public static String middlePairedBlockMessage(List<Voxel> source, Voxel.Axis axis,
            int min, int original, int start, int end) {
        for (Voxel v : source) {
            int local = coord(v, axis) - min;
            if (local >= start && local < original - end && isPairedBlock(v.blockState())) {
                return axis + " 方向不能重复：可变区包含双格高的门结构或床等配对方块；请锁定该方向";
            }
        }
        return null;
    }

    private static VoxelGrid rebuild(List<Voxel> source, Voxel.Axis axis, Mapping map) {
        // Index whole slices, including gaps. Repeating a two-slice motif must not multiply
        // the requested length by two or fill deliberate empty slices.
        List<List<Voxel>> slices = new ArrayList<>(map.originalSize());
        for (int i = 0; i < map.originalSize(); i++) slices.add(new ArrayList<>());
        for (Voxel v : source) slices.get(coord(v, axis) - map.min()).add(v);
        VoxelGrid out = new VoxelGrid();
        int anchorShift = map.position(0);
        for (int destination = 0; destination < map.targetSize(); destination++) {
            int from = map.sourceSliceForDestination(destination);
            int coordinate = map.min() + destination - anchorShift;
            for (Voxel voxel : slices.get(from)) {
                if (out.size() >= MAX_BLOCKS) throw new IllegalArgumentException("Adapted component exceeds block budget");
                out.add(voxel.shift(axis, coordinate - coord(voxel, axis)));
            }
        }
        return out;
    }

    public static int coord(Voxel v, Voxel.Axis axis) {
        return switch (axis) { case X -> v.x(); case Y -> v.y(); case Z -> v.z(); };
    }
}
