package com.formacraft.client.ui.panel.capture;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.placement.AttachmentType;

/** Panel callbacks for {@link ComponentCaptureSemanticSection}. */
public interface ComponentCaptureSemanticHost {
    String getCategoryDisplayName(ComponentCategory category);

    String getAttachmentModeDisplay();

    AttachmentType getAttachmentMode();

    void updateTagsFromInput();

    void setNameInputBounds(int x, int y, int w, int h);

    void setTagsInputBounds(int x, int y, int w, int h);

    boolean isAdvancedOptionsExpanded();

    void setAdvancedOptionsExpanded(boolean expanded);
}
