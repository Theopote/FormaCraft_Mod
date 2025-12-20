package com.formacraft.common.patch;

import java.util.List;

/**
 * PatchSpec：一组针对 origin 的方块修改。
 * <p>
 * 注意：origin 不一定等于玩家位置，通常由选区最小点/工具确定。
 */
public class PatchSpec {
    private List<BlockPatch> patches;

    public PatchSpec() {}

    public PatchSpec(List<BlockPatch> patches) {
        this.patches = patches;
    }

    public List<BlockPatch> getPatches() {
        return patches;
    }

    public void setPatches(List<BlockPatch> patches) {
        this.patches = patches;
    }
}

