package com.formacraft.common.component;

import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.query.ComponentQueryMatchUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;

/** Classification is descriptive metadata; suggested placement never grants transformation permissions. */
public final class ComponentTypes {
    private ComponentTypes() {}
    public record Ref(String key, String label) {}
    public record Preset(Ref type, ComponentCategory category, AttachmentType attachment, boolean insideOutside, boolean bottomTop) {}
    public static final List<Preset> PRESETS = List.of(
            preset("door", "门", ComponentCategory.DOOR, AttachmentType.WALL_OPENING, true, false),
            preset("window", "窗", ComponentCategory.WINDOW, AttachmentType.WALL_OPENING, true, false),
            preset("balcony", "阳台", ComponentCategory.BALCONY, AttachmentType.WALL_SURFACE, true, false),
            preset("railing", "栏杆", ComponentCategory.RAILING, AttachmentType.EDGE, false, false),
            preset("panel", "面板", ComponentCategory.PANEL, AttachmentType.WALL_SURFACE, false, false),
            preset("column", "柱", ComponentCategory.COLUMN, AttachmentType.FLOOR, false, false),
            preset("decorative_column", "装饰柱", ComponentCategory.COLUMN, AttachmentType.FLOOR, false, false),
            preset("stairs", "楼梯", ComponentCategory.STAIRS, AttachmentType.FLOOR, false, true),
            preset("bracket", "斗拱", ComponentCategory.BRACKET, AttachmentType.WALL_SURFACE, false, false),
            preset("arch", "拱券", ComponentCategory.ARCH, AttachmentType.NONE, false, false),
            preset("ornament", "装饰", ComponentCategory.ORNAMENT, AttachmentType.WALL_SURFACE, false, false),
            preset("roof_detail", "屋顶细部", ComponentCategory.ROOF_DETAIL, AttachmentType.ROOF_SURFACE, false, false),
            preset("chimney", "烟囱", ComponentCategory.ROOF_DETAIL, AttachmentType.ROOF_SURFACE, false, false),
            preset("ridge", "屋脊", ComponentCategory.ROOF_DETAIL, AttachmentType.ROOF_RIDGE, false, false),
            preset("generic", "通用构件", ComponentCategory.GENERIC, AttachmentType.NONE, false, false));
    private static Preset preset(String key, String label, ComponentCategory category, AttachmentType attachment, boolean io, boolean bt) {
        return new Preset(new Ref("formacraft:" + key, label), category, attachment, io, bt);
    }
    public static Preset preset(String key) { return PRESETS.stream().filter(p -> p.type.key.equals(key)).findFirst().orElse(null); }
    public static Preset base(ComponentCategory category) {
        var actual = category == null ? ComponentCategory.GENERIC : category;
        return PRESETS.stream().filter(p -> p.category == actual).findFirst().orElseThrow();
    }
    public static Ref custom(String name) {
        if (name != null && name.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
            throw new IllegalArgumentException("自定义类型名称不能包含控制字符");
        String label = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKC).strip().replaceAll("[\\s\\p{Z}]+", " ");
        if (label.isBlank() || label.length() > 64 || label.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("自定义类型名称需为 1–64 字符，不能包含控制字符");
        try {
            String normalized = ComponentQueryMatchUtil.normalizeTag(label);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
            return new Ref("custom:" + hash, label);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String error(Ref type, ComponentCategory category) {
        if (type == null) return null;
        if (type.key == null || type.label == null) return "构件类型缺少标识或名称";
        var preset = preset(type.key);
        if (preset != null) {
            if (preset.category != category) return "预设类型与基础类不一致";
            try { custom(type.label); return null; } catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
        }
        try { return type.equals(custom(type.label)) ? null : "自定义类型标识与名称不一致"; }
        catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
    }
    public static Ref effective(Ref type, ComponentCategory category) { return type == null ? base(category).type : type; }
    public static boolean matches(String requested, Ref type, ComponentCategory category) {
        if (requested == null || requested.isBlank()) return true;
        var actual = effective(type, category);
        return actual.key.equalsIgnoreCase(requested.strip())
                || ComponentQueryMatchUtil.normalizeTag(actual.label).equals(ComponentQueryMatchUtil.normalizeTag(requested));
    }
}
