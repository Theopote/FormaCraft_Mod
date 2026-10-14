package com.formacraft.common.patch;

/**
 * Formal patch-confirm execution outcome (not best-effort cell counts).
 */
public enum BuildExecutionStatus {
    /** All cells applied and verified; safe to record history. */
    SUCCESS,
    /** Height / chunk / target / incomplete before-snapshot — zero writes. */
    PREFLIGHT_REJECTED,
    /** Apply started but failed; recovery not yet classified. */
    APPLY_FAILED,
    /** Apply failed; every before-snapshot restored as FULL. */
    ROLLED_BACK,
    /** Apply failed; recovery had STATE_ONLY / REJECTED / ERROR. */
    ROLLBACK_INCOMPLETE;

    public boolean recordsHistory() {
        return this == SUCCESS;
    }

    public String labelZh() {
        return switch (this) {
            case SUCCESS -> "建造成功";
            case PREFLIGHT_REJECTED -> "建造已拒绝（预检查失败）";
            case APPLY_FAILED -> "建造失败";
            case ROLLED_BACK -> "建造失败，已回滚";
            case ROLLBACK_INCOMPLETE -> "建造失败，回滚不完整";
        };
    }
}
