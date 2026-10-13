package com.formacraft.common.world;

/**
 * 快照写入结果分级：含 BE NBT 的目标必须以 {@link #FULL} 才算完整成功。
 */
public enum SnapshotWriteResult {
    /** 状态与（若有）NBT 均已对齐目标快照 */
    FULL,
    /** 仅 BlockState 恢复成功，NBT 未完整恢复 */
    STATE_ONLY,
    /** setBlockState 拒绝 */
    REJECTED,
    /** 写入或读回过程异常 */
    ERROR;

    public boolean isFullSuccess() {
        return this == FULL;
    }

    public boolean changedWorldPossibly() {
        return this == FULL || this == STATE_ONLY;
    }
}
