package com.formacraft.common.mass;

/**
 * WingAttachment（翼楼附着）
 * <p>
 * 定义翼楼体量如何附着在主体上
 * <p>
 * ⚠️ 关键：
 * - 这是离散的 block 偏移
 * - 没有向量、没有角度
 * - 只关心相对位置和附着侧
 */
public class WingAttachment {
    /** 依附的体量 ID（主体） */
    public final String hostMassId;

    /** 附着在哪一侧 */
    public final AttachmentSide side;

    /** 前后错动（block，离散的方块数） */
    public final int offset;

    public WingAttachment(String hostMassId, AttachmentSide side, int offset) {
        this.hostMassId = hostMassId;
        this.side = side;
        this.offset = offset;
    }

    /**
     * 创建无错动的翼楼附着
     */
    public static WingAttachment attached(String hostMassId, AttachmentSide side) {
        return new WingAttachment(hostMassId, side, 0);
    }
}
