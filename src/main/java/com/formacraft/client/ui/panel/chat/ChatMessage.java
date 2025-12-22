package com.formacraft.client.ui.panel.chat;

import com.formacraft.common.model.build.BuildingSpec;

/**
 * FormaCraft 聊天系统中的一条消息。
 *
 * 支持：
 * - 文本消息（玩家、AI 都可）
 * - 可选的结构化结果（如 AI 返回的 BuildingSpec）
 * - 易扩展，用于未来加入 CitySpec、PathSpec 等模型
 */
public class ChatMessage {

    /** 展示文本（已解析，可直接用于聊天气泡） */
    public final String text;

    /** 是否玩家发出的消息（否则为 AI 消息） */
    public final boolean fromPlayer;

    /** 可选：AI 的结构化建筑结果（用于摘要、预览、确认建造） */
    public final BuildingSpec spec;

    /** 可选：消息类型（未来可扩展：TEXT, SPEC, TOOL_CALL, ERROR 等） */
    public final MessageType type;

    /** 可选：时间戳（毫秒） */
    public final long timestamp;

    public enum MessageType {
        TEXT,
        SPEC,
        ERROR,
        SYSTEM,
        THINKING,   // AI 正在思考（点点点动画）
        CANCELLED,  // 已中断
        STREAMING   // 流式打印中的消息
    }

    /** 最常用构造（普通文本消息） */
    public ChatMessage(String text, boolean fromPlayer) {
        this(text, fromPlayer, null, MessageType.TEXT);
    }

    /** AI 结构化消息（比如 BuildingSpec） */
    public ChatMessage(String text, boolean fromPlayer, BuildingSpec spec) {
        this(text, fromPlayer, spec, MessageType.SPEC);
    }

    public ChatMessage(String text, boolean fromPlayer, BuildingSpec spec, MessageType type) {
        this.text = text;
        this.fromPlayer = fromPlayer;
        this.spec = spec;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean hasSpecSummary() {
        return spec != null;
    }

    /**
     * 创建流式打印消息
     */
    public static ChatMessage streaming(String text, BuildingSpec spec) {
        return new ChatMessage(text, false, spec, MessageType.STREAMING);
    }

    /**
     * 创建“正在思考”占位消息（用于 HUD 中显示点点点动画）
     */
    public static ChatMessage thinking() {
        return new ChatMessage("", false, null, MessageType.THINKING);
    }

    /** 可自定义的 thinking 占位（用于更明确的状态提示） */
    public static ChatMessage thinking(String label) {
        return new ChatMessage(label != null ? label : "", false, null, MessageType.THINKING);
    }

    public static ChatMessage error(String text) {
        return new ChatMessage(text != null ? text : "", false, null, MessageType.ERROR);
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(text != null ? text : "", false, null, MessageType.SYSTEM);
    }

    public static ChatMessage cancelled(String text) {
        return new ChatMessage(text != null ? text : "", false, null, MessageType.CANCELLED);
    }
}
