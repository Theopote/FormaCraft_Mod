package com.formacraft.common.component.model;

import java.util.List;

/**
 * variants.json（可选）：
 * 用于在一个 prototype 目录下预置一组可选变体（便于 UI 一键选择或 AI 推荐）。
 *
 * <pre>
 * {
 *   "schema": "formacraft.component.variant_presets.v1",
 *   "prototype_id": "gothic_door",
 *   "variants": [ { ...PersistedComponentVariant... }, ... ]
 * }
 * </pre>
 */
public class ComponentVariantPresetList {
    public String schema = "formacraft.component.variant_presets.v1";
    public String prototype_id;
    public List<PersistedComponentVariant> variants;
}

