package com.formacraft.common.network;

import java.util.regex.Pattern;

/** Shared raw-user intent safeguard; never inspect the assembled schema prompt. */
public final class IncrementalRequestIntent {
    private IncrementalRequestIntent() {}

    private static final Pattern EDIT_EXISTING = Pattern.compile(
            "(?:修改|改动|调整|编辑|修补)\\s*(?:刚才|之前|已有|现有|当前|这栋|这座|这个|那栋|那座|上一次)"
                    + "|(?:只|仅)(?:在|把|将).{0,60}(?:增加|新增|开窗|去掉|移除|替换).{0,100}(?:保持|保留)"
                    + "|(?:只|仅)(?:去掉|移除|删除|修改|替换|更换|增加).{0,24}(?:刚才|之前|已有|现有|当前|这栋|这座)"
                    + "|正门右.{0,40}(?:增加|添加).{0,40}玻璃窗"
                    + "|其余结构不动|其余不动|不要移动建筑"
                    + "|(?:modify|edit|update)\\s+(?:the\\s+)?(?:previous|existing|current|last)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static boolean isIncremental(String text) {
        return text != null && EDIT_EXISTING.matcher(text.replaceAll("\\s+", " ")).find();
    }
}
