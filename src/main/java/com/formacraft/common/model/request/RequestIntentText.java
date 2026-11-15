package com.formacraft.common.model.request;

/** User intent precedence: userMessage, last chat entry, then requestText. */
public final class RequestIntentText {
    private RequestIntentText() {}

    public static String resolve(FormaRequest req) {
        if (req == null) {
            return "";
        }
        String userMessage = req.getUserMessage();
        if (userMessage != null && !userMessage.isBlank()) {
            return userMessage;
        }
        try {
            java.util.List<String> history = req.getChatHistory();
            if (history != null && !history.isEmpty()) {
                String last = history.get(history.size() - 1);
                if (last != null && !last.isBlank()) {
                    return last;
                }
            }
        } catch (Exception ignored) {
            // 聊天记录不可用时忽略，继续兜底
        }
        String requestText = req.getRequestText();
        return requestText != null ? requestText : "";
    }
}
