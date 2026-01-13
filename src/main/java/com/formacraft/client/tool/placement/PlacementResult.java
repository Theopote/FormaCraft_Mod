package com.formacraft.client.tool.placement;

/**
 * PlacementResult：合法性检查结果（用于渲染/交互）。
 */
public class PlacementResult {
    public enum Status {
        VALID,
        WARN,
        INVALID
    }

    public final Status status;
    public final String reason;

    private PlacementResult(Status s, String r) {
        this.status = s;
        this.reason = r;
    }

    public static PlacementResult valid() {
        return new PlacementResult(Status.VALID, null);
    }

    public static PlacementResult warn(String reason) {
        return new PlacementResult(Status.WARN, reason);
    }

    public static PlacementResult invalid(String reason) {
        return new PlacementResult(Status.INVALID, reason);
    }

    public boolean isValid() {
        return status == Status.VALID;
    }
}

