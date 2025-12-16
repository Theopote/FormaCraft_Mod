package com.formacraft.ai;

public interface AIService {
    AIResult generateBuildingPlan(BuildingRequest request);

    /**
     * 可取消版本：Stop 会通过 token + 线程中断让网络请求真正停止。
     * 返回 null 表示被取消（调用方应把消息标记为“已中断”而非报错）。
     */
    default AIResult generateBuildingPlan(BuildingRequest request, AICancelToken token) {
        if (token != null && token.isCancelled()) return null;
        return generateBuildingPlan(request);
    }
}
