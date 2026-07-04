package com.formacraft.common.model.request;

/**
 * PR-4: 用户附带的参考资料（图片 URL、base64、网页链接）。
 * 与 Python {@code ReferenceInput} / {@code BuildRequest.references} 对齐。
 */
public class ReferenceInput {
    /** image_url | image_base64 | web_url */
    private String type;
    private String content;
    private String caption;

    public ReferenceInput() {
    }

    public ReferenceInput(String type, String content, String caption) {
        this.type = type;
        this.content = content;
        this.caption = caption;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }
}
