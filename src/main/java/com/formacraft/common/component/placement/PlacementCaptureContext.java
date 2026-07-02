package com.formacraft.common.component.placement;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 捕获阶段传入的放置上下文（由 {@code ComponentCaptureDraft} 转换而来）。
 */
public final class PlacementCaptureContext {
  public AttachmentType userAttachment = AttachmentType.NONE;
  /** 用户是否手动切换过附着模式（优先级最高） */
  public boolean userAttachmentManual = false;

  public boolean hasInteriorExterior = false;
  public boolean hasBottomTop = false;
  public boolean hasInsideOutsideMarks = false;
  public boolean hasBottomTopMarks = false;
  public boolean hasHostFace = false;

  public static PlacementCaptureContext createDefault() {
    return new PlacementCaptureContext();
  }
}
