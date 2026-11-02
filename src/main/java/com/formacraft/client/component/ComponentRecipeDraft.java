package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.component.variant.SegmentScaler;
import java.util.Objects;

/** UI values are hints only; the server revalidates exact fit and author permissions. */
public final class ComponentRecipeDraft {
    private ComponentCatalog.Entry entry;
    private final int[] sizes = new int[3];
    public void bind(ComponentCatalog.Entry selected) {
        if (entry != null && selected != null && Objects.equals(entry.id,selected.id) && Objects.equals(entry.packageRef,selected.packageRef)) return;
        entry = selected;
        if (entry != null && entry.size != null) { sizes[0] = entry.size.w; sizes[1] = entry.size.h; sizes[2] = entry.size.d; }
    }
    public int size(int axis) { return sizes[axis]; }
    public boolean canAdjust(int axis,int delta) {
        if (entry == null || entry.size == null || axis < 0 || axis > 2 || entry.variantRules == null || entry.variantRules.scaling == null || entry.variantRules.scaling.axes == null) return false;
        var rule = entry.variantRules.scaling.axes.get(new String[]{"X","Y","Z"}[axis]);
        if (rule == null || "FIXED".equalsIgnoreCase(rule.type)) return false;
        int original = new int[]{entry.size.w,entry.size.h,entry.size.d}[axis];
        int min = Math.max(1,(rule.fixedStart == null ? 1 : rule.fixedStart)+(rule.fixedEnd == null ? 1 : rule.fixedEnd)+1);
        if (rule.min != null) min = Math.max(min,rule.min);
        int max = "TRIM".equalsIgnoreCase(rule.type) ? original : SegmentScaler.MAX_AXIS_SIZE;
        if (rule.max != null) max = Math.min(max,rule.max);
        long target = (long)sizes[axis]+delta; return target >= min && target <= max;
    }
    public void adjust(int axis,int delta) { if (canAdjust(axis,delta)) sizes[axis] += delta; }
    public ComponentRecipeLibrary.Spec spec() {
        String name = entry == null || entry.name == null || entry.name.isBlank() ? "构件配方" : entry.name;
        if (name.length() > 32) name = name.substring(0,Character.isHighSurrogate(name.charAt(31)) ? 31 : 32);
        return new ComponentRecipeLibrary.Spec(name+" "+sizes[0]+"×"+sizes[1]+"×"+sizes[2],sizes[0],sizes[1],sizes[2]);
    }
}
