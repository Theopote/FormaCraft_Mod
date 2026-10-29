package com.formacraft.client.ai.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptModeResolverTest {

    @Test
    void loggedCabinEditDoesNotBecomeBuild() {
        assertEquals(PromptMode.PATCH, PromptModeResolver.fromUserText(
                "修改刚才的小木屋：只在正门右\n侧的墙面增加一扇2格宽、\n2格高的玻璃窗。保持建筑位置、地基、屋顶、门和其他墙面不变。"));
    }

    @Test
    void incrementalChineseMapsToPatch() {
        assertEquals(PromptMode.PATCH, PromptModeResolver.fromUserText(
                "只改局部：把这栋小木屋朝南外墙最中间那一段改成玻璃窗带，不要重建整栋"));
        assertEquals(PromptMode.PATCH, PromptModeResolver.fromUserText(
                "对当前建筑做增量修改：南立面中部开三格宽的玻璃窗"));
        assertEquals(PromptMode.PATCH, PromptModeResolver.fromUserText("把屋顶换成红色"));
        assertEquals(PromptMode.PATCH, PromptModeResolver.fromUserText(
                "在正门右侧添加一扇 3格宽 2格高的玻璃窗，其余结构不动。"));
        assertEquals(PromptMode.PATCH, PromptModeResolver.fromUserText(
                "修改上一次建筑：在正门右侧添加一扇 3格宽 2格高的玻璃窗，其余结构不动。"));
    }

    @Test
    void regionLanguageMapsToModifyRegion() {
        assertEquals(PromptMode.MODIFY_REGION, PromptModeResolver.fromUserText("仅修改选区内的方块"));
    }

    @Test
    void normalBuildStaysBuild() {
        assertEquals(PromptMode.BUILD, PromptModeResolver.fromUserText(
                "盖一个 9x7 的单层小木屋，橡木墙，深色橡木屋顶"));
        assertEquals(PromptMode.BUILD, PromptModeResolver.fromUserText("在锚点位置生成大雁塔"));
        assertEquals(PromptMode.BUILD, PromptModeResolver.fromUserText(""));
        assertEquals(PromptMode.BUILD, PromptModeResolver.fromUserText(null));
    }
}
