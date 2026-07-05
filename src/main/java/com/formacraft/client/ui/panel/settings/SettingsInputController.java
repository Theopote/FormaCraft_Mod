package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.MouseInput;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/**
 * 设置面板的鼠标交互（点击命中 / 拖拽 / 释放 / 滚动）。
 * <p>
 * 从 {@code SettingsPanel} 拆出，仅通过 {@link SettingsPanelRenderHost} 读写面板状态，
 * 布局命中逻辑必须与 {@code SettingsPanel.drawContents} / 各 Section 保持一致。
 */
public final class SettingsInputController {
    private SettingsInputController() {}

    /**
     * 处理面板内点击（顶部 Tab 切换由 {@code SettingsPanel} 的 super 调用先行处理）。
     * @return true 表示已消费
     */
    public static boolean handleClick(SettingsPanelRenderHost host, double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        host.ensureWidgets();
        int x = host.contentStartX();
        int y = host.contentTopY() + CONTENT_PADDING - host.scrollY();
        int w = host.contentWidth();

        boolean clickedOutside = !host.isMouseOver(mouseX, mouseY);
        // 防御：如果点击在面板外，不应继续命中任何控件（否则会“隔空点按钮/输入框”）
        if (clickedOutside) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.searchApiKeyInput().setFocused(false);
            host.googleCseCxInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return false;
        }

        // drawContents 里先画标题，再 y += TITLE_HEIGHT
        y += TITLE_HEIGHT;

        // 分组：LLM 与后端
        y += sectionHeaderHeight(host.client());

        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // 优先处理 overlay 下拉（它们渲染在最上层，必须先命中，避免被下层输入框吞掉点击）
        if (host.baseUrlPresetDropdownOpen() && host.pendingBaseUrlDropdownOverlay()) {
            int dx = host.pendingBaseUrlDropdownX();
            int dy = host.pendingBaseUrlDropdownY();
            int dw = host.pendingBaseUrlDropdownW();
            host.layoutBaseUrlPresetButtons(dx, dy, dw);

            boolean clickedAny = false;
            int visibleCount = 0;
            for (ButtonWidget opt : host.llmBaseUrlPresetOptionButtons()) {
                if (opt != null && opt.visible) {
                    visibleCount++;
                    if (opt.mouseClicked(click, false)) {
                        clickedAny = true;
                        break;
                    }
                }
            }
            if (clickedAny) return true;

            int listH = visibleCount * BUTTON_HEIGHT;
            boolean insideList = (mouseX >= dx && mouseX <= dx + dw && mouseY >= dy && mouseY <= dy + listH);
            if (!insideList) {
                host.setBaseUrlPresetDropdownOpen(false);
                host.hideBaseUrlPresetButtons();
            }
            return true;
        }

        if (host.modelDropdownOpen() && host.pendingModelDropdownOverlay()) {
            int dx = host.pendingModelDropdownX();
            int dy = host.pendingModelDropdownY();
            int dw = host.pendingModelDropdownW();
            host.layoutModelOptionButtons(dx, dy, dw);

            boolean clickedAny = false;
            int visibleCount = 0;
            for (ButtonWidget opt : host.modelOptionButtons()) {
                if (opt != null && opt.visible) {
                    visibleCount++;
                    if (opt.mouseClicked(click, false)) {
                        clickedAny = true;
                        break;
                    }
                }
            }
            if (clickedAny) return true;

            int listH = visibleCount * BUTTON_HEIGHT;
            boolean insideList = (mouseX >= dx && mouseX <= dx + dw && mouseY >= dy && mouseY <= dy + listH);
            if (!insideList) {
                host.setModelDropdownOpen(false);
                host.hideModelOptionButtons();
            }
            return true;
        }

        // =========== Orchestrator 区域 ============
        int orchLabelY = y;
        int orchY = orchLabelY + LABEL_OFFSET;
        if (host.orchestratorInput().mouseClicked(mouseX, mouseY, x, orchY, w, INPUT_HEIGHT)) {
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // =========== API Key 区域 ============
        // Orchestrator 是"标题+输入框"两行：渲染端推进 LABEL_OFFSET(输入框行) + FIELD_SPACING。
        // 命中测试必须与渲染一致，否则从 API Key 起，下方所有控件的命中框会整体上移一个 LABEL_OFFSET(18px)，
        // 表现为"点在按钮上，却触发了下面那个按钮"。
        y += LABEL_OFFSET + FIELD_SPACING;
        int apiLabelY = y;
        int apiY = apiLabelY + LABEL_OFFSET;
        int apiBtnY = apiLabelY + LABEL_OFFSET * 2;
        int gap = BUTTON_GAP_SMALL;
        int apiBtnW = Math.max(0, (w - gap * 2) / 3);
        int apiBtnW3 = Math.max(0, w - apiBtnW * 2 - gap * 2);
        int pasteX = x + apiBtnW + gap;
        int testX = x + apiBtnW * 2 + gap * 2;

        // Show/Hide（原版按钮）
        host.showHideButton().setPosition(x, apiBtnY);
        host.showHideButton().setWidth(apiBtnW);
        if (host.showHideButton().mouseClicked(click, false)) {
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // Paste（原版按钮）
        host.pasteButton().setPosition(pasteX, apiBtnY);
        host.pasteButton().setWidth(apiBtnW);
        if (host.pasteButton().mouseClicked(click, false)) {
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // Test Key（即时鉴权）
        host.testKeyButton().setPosition(testX, apiBtnY);
        host.testKeyButton().setWidth(apiBtnW3);
        if (host.testKeyButton().mouseClicked(click, false)) {
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // 点击 API key 输入框
        if (host.apiKeyInput().mouseClicked(mouseX, mouseY, x, apiY, w, INPUT_HEIGHT)) {
            host.orchestratorInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // =========== LLM Provider ============
        // API Key 三行：标题 + 输入框 + 按钮行
        y = afterThreeRowField(apiLabelY);
        int providerLabelY = y;
        int providerY = providerLabelY + LABEL_OFFSET;

        host.llmProviderButton().setPosition(x, providerY);
        host.llmProviderButton().setWidth(w);
        if (host.llmProviderButton().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // =========== LLM Base URL ============
        y = afterTwoRowField(providerLabelY);
        int llmBaseUrlLabelY = y;
        int llmBaseUrlPresetY = llmBaseUrlLabelY + LABEL_OFFSET;
        int llmBaseUrlThirdLineY = llmBaseUrlLabelY + LABEL_OFFSET * 2;

        // 预设按钮（第二行）
        host.llmBaseUrlPresetButton().setPosition(x, llmBaseUrlPresetY);
        host.llmBaseUrlPresetButton().setWidth(w);
        if (host.llmBaseUrlPresetButton().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // 下拉展开：命中选项
        if (host.baseUrlPresetDropdownOpen()) {
            // 展开应紧贴预设按钮下方（与渲染 overlay 一致）
            int listY0 = llmBaseUrlPresetY + BUTTON_HEIGHT;
            host.layoutBaseUrlPresetButtons(x, listY0, w);

            boolean clickedAny = false;
            for (ButtonWidget opt : host.llmBaseUrlPresetOptionButtons()) {
                if (opt != null && opt.visible && opt.mouseClicked(click, false)) {
                    clickedAny = true;
                    break;
                }
            }
            if (clickedAny) {
                return true;
            }

            int listH = host.llmBaseUrlPresetOptionButtons().size() * BUTTON_HEIGHT;
            boolean insideList = (mouseX >= x && mouseX <= x + w && mouseY >= listY0 && mouseY <= listY0 + listH);
            if (!insideList) {
                // 点击列表外：收起（并吞掉点击，避免“穿透”点到下面控件）
                host.setBaseUrlPresetDropdownOpen(false);
                host.hideBaseUrlPresetButtons();
                host.modelInput().setFocused(false);
                host.setModelDropdownOpen(false);
                host.hideModelOptionButtons();
                return true;
            }

            // 点在列表区域但没点到按钮：吞掉（避免误触其它控件）
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // 第三行：只有自定义时才允许点输入框
        SettingsBaseUrlPresets.Preset p = host.selectedBaseUrlPreset();
        if (p != null && p.url() == null) {
            if (host.llmBaseUrlInput().mouseClicked(mouseX, mouseY, x, llmBaseUrlThirdLineY, w, INPUT_HEIGHT)) {
                host.orchestratorInput().setFocused(false);
                host.apiKeyInput().setFocused(false);
                host.modelInput().setFocused(false);
                host.setModelDropdownOpen(false);
                host.hideModelOptionButtons();
                return true;
            }
        } else {
            host.llmBaseUrlInput().setFocused(false);
        }

        // =========== Model（输入 + 刷新 + 自动） ============
        y = afterThreeRowField(llmBaseUrlLabelY);
        int modelLabelY = y;
        int modelInputY = modelLabelY + LABEL_OFFSET;
        int modelBtnY = modelLabelY + LABEL_OFFSET * 2;

        // 下拉展开：命中选项（优先处理，避免“穿透”）
        if (host.modelDropdownOpen()) {
            int listY0 = modelInputY + INPUT_HEIGHT;
            host.layoutModelOptionButtons(x, listY0, w);

            boolean clickedAny = false;
            int visibleCount = 0;
            for (ButtonWidget opt : host.modelOptionButtons()) {
                if (opt != null && opt.visible) {
                    visibleCount++;
                    if (opt.mouseClicked(click, false)) {
                        clickedAny = true;
                        break;
                    }
                }
            }
            if (clickedAny) return true;

            int listH = visibleCount * BUTTON_HEIGHT;
            boolean insideList = (mouseX >= x && mouseX <= x + w && mouseY >= listY0 && mouseY <= listY0 + listH);
            boolean insideInput = (mouseX >= x && mouseX <= x + w && mouseY >= modelInputY && mouseY <= modelInputY + INPUT_HEIGHT);
            if (!insideList && !insideInput) {
                // 点击列表外：收起并吞掉点击，避免“穿透”
                host.setModelDropdownOpen(false);
                host.hideModelOptionButtons();
                return true;
            }
            // 点在列表区域但没点到按钮：吞掉
            if (insideList) return true;
        }

        // 点击模型输入框
        if (host.modelInput().mouseClicked(mouseX, mouseY, x, modelInputY, w, INPUT_HEIGHT)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            // 输入框聚焦时，如果有候选则展开
            host.setModelDropdownOpen(!host.availableModelsEmpty());
            host.setDraftModel(host.modelInput().getText() == null ? "" : host.modelInput().getText().trim());
            return true;
        }

        int gap2 = BUTTON_GAP_SMALL;
        int modelBtnW1 = (w - gap2) / 2;
        int modelBtnW2 = Math.max(0, w - gap2 - modelBtnW1);

        host.detectModelButton().setPosition(x, modelBtnY);
        host.detectModelButton().setWidth(modelBtnW1);
        if (host.detectModelButton().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        host.autoModelButton().setPosition(x + modelBtnW1 + gap2, modelBtnY);
        host.autoModelButton().setWidth(modelBtnW2);
        if (host.autoModelButton().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // =========== Search（建筑研究） ============
        y = afterThreeRowField(modelLabelY);
        y += sectionHeaderHeight(host.client());

        int searchProviderLabelY = y;
        int searchProviderY = searchProviderLabelY + LABEL_OFFSET;
        host.searchProviderButton().setPosition(x, searchProviderY);
        host.searchProviderButton().setWidth(w);
        if (host.searchProviderButton().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.searchApiKeyInput().setFocused(false);
            host.googleCseCxInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        y += FIELD_SPACING;
        int searchKeyLabelY = y;
        int searchKeyY = searchKeyLabelY + LABEL_OFFSET;
        if (host.searchApiKeyInput().mouseClicked(mouseX, mouseY, x, searchKeyY, w, INPUT_HEIGHT)) {
            host.orchestratorInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.googleCseCxInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        int searchTestBtnY = searchKeyY + LABEL_OFFSET;
        host.testSearchKeyButton().setPosition(x, searchTestBtnY);
        host.testSearchKeyButton().setWidth(w);
        if (host.testSearchKeyButton().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.searchApiKeyInput().setFocused(false);
            host.googleCseCxInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // Search API Key 三行之后进入 Google CX
        y = afterThreeRowField(searchKeyLabelY);
        int googleCxLabelY = y;
        int googleCxY = googleCxLabelY + LABEL_OFFSET;
        if (host.googleCseCxInput().mouseClicked(mouseX, mouseY, x, googleCxY, w, INPUT_HEIGHT)) {
            host.orchestratorInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.searchApiKeyInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        y = afterTwoRowField(googleCxLabelY);
        y += sectionHeaderHeight(host.client());

        // =========== Debug Warnings（toggle） ============
        int debugLabelY = y;
        int dbgBtnY = debugLabelY + LABEL_OFFSET;
        host.debugWarningsButton().setPosition(x, dbgBtnY);
        host.debugWarningsButton().setWidth(w);
        if (host.debugWarningsButton().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.llmBaseUrlInput().setFocused(false);
            host.modelInput().setFocused(false);
            host.setModelDropdownOpen(false);
            host.hideModelOptionButtons();
            return true;
        }

        // 滑条字段：与 PreferencesSection 一致，两行一组
        y = afterTwoRowField(debugLabelY);
        int reachLabelY = y;
        int reachSliderY = reachLabelY + LABEL_OFFSET;
        host.interactionReachSlider().setPosition(x, reachSliderY);
        host.interactionReachSlider().setWidth(w);
        if (host.interactionReachSlider().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.setActiveSlider(host.interactionReachSlider());
            return true;
        }

        y = afterTwoRowField(reachLabelY);
        int tempLabelY = y;
        int tempSliderY = tempLabelY + LABEL_OFFSET;
        host.temperatureSlider().setPosition(x, tempSliderY);
        host.temperatureSlider().setWidth(w);
        if (host.temperatureSlider().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.setActiveSlider(host.temperatureSlider());
            return true;
        }

        y = afterTwoRowField(tempLabelY);
        int fontLabelY = y;
        int fontSliderY = fontLabelY + LABEL_OFFSET;
        host.fontSizeSlider().setPosition(x, fontSliderY);
        host.fontSizeSlider().setWidth(w);
        if (host.fontSizeSlider().mouseClicked(click, false)) {
            host.orchestratorInput().setFocused(false);
            host.apiKeyInput().setFocused(false);
            host.setActiveSlider(host.fontSizeSlider());
            return true;
        }

        // =========== 按钮行（Save/Cancel/Reset） ============
        y = afterTwoRowField(fontLabelY);
        y += sectionHeaderHeight(host.client());
        int btnY = y;
        int btnW1 = (w - BUTTON_GAP * 2) / 3;
        int btnW3 = Math.max(0, w - (btnW1 + btnW1 + BUTTON_GAP * 2));
        int cancelX = x + btnW1 + BUTTON_GAP;
        int resetX = cancelX + btnW1 + BUTTON_GAP;

        host.saveButton().setPosition(x, btnY);
        host.saveButton().setWidth(btnW1);
        if (host.saveButton().mouseClicked(click, false)) return true;

        host.cancelButton().setPosition(cancelX, btnY);
        host.cancelButton().setWidth(btnW1);
        if (host.cancelButton().mouseClicked(click, false)) return true;

        host.resetButton().setPosition(resetX, btnY);
        host.resetButton().setWidth(btnW3);
        return host.resetButton().mouseClicked(click, false);
    }

    public static boolean handleDrag(SettingsPanelRenderHost host, double mouseX, double mouseY,
                                     int button, double deltaX, double deltaY) {
        host.ensureWidgets();
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        // 只允许拖动一个滑条；且仅当鼠标位于滑条上时才更新（用户期望）
        SliderWidget active = host.activeSlider();
        if (active == null) return false;
        if (!active.isMouseOver(mouseX, mouseY)) return false;
        return active.mouseDragged(click, deltaX, deltaY);
    }

    public static boolean handleRelease(SettingsPanelRenderHost host, double mouseX, double mouseY, int button) {
        host.ensureWidgets();
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        boolean handled = false;
        SliderWidget active = host.activeSlider();
        if (active != null) {
            handled = active.mouseReleased(click);
            host.setActiveSlider(null);
        } else {
            // 兜底：如果未记录 activeSlider，也尝试释放三者，防止残留拖拽状态
            if (host.temperatureSlider() != null) handled |= host.temperatureSlider().mouseReleased(click);
            if (host.fontSizeSlider() != null) handled |= host.fontSizeSlider().mouseReleased(click);
            if (host.interactionReachSlider() != null) handled |= host.interactionReachSlider().mouseReleased(click);
        }
        return handled;
    }

    public static void handleScroll(SettingsPanelRenderHost host, double mouseX, double mouseY, double amount) {
        if (!host.isMouseOver(mouseX, mouseY)) return;

        int x = host.contentStartX();
        int y = host.contentTopY() + CONTENT_PADDING - host.scrollY();
        int w = host.contentWidth();

        y += TITLE_HEIGHT;
        y += sectionHeaderHeight(host.client());

        int orchLabelY = y;
        if (host.orchestratorInput().mouseScrolled(mouseX, mouseY, amount, x, orchLabelY + LABEL_OFFSET, w, INPUT_HEIGHT)) {
            return;
        }

        y += LABEL_OFFSET + FIELD_SPACING;
        int apiLabelY = y;
        if (host.apiKeyInput().mouseScrolled(mouseX, mouseY, amount, x, apiLabelY + LABEL_OFFSET, w, INPUT_HEIGHT)) {
            return;
        }

        y = afterThreeRowField(apiLabelY);
        int providerLabelY = y;
        y = afterTwoRowField(providerLabelY);
        int llmBaseUrlLabelY = y;
        SettingsBaseUrlPresets.Preset p = host.selectedBaseUrlPreset();
        if (p != null && p.url() == null) {
            if (host.llmBaseUrlInput().mouseScrolled(mouseX, mouseY, amount, x, llmBaseUrlLabelY + LABEL_OFFSET * 2, w, INPUT_HEIGHT)) {
                return;
            }
        }

        y = afterThreeRowField(llmBaseUrlLabelY);
        int modelLabelY = y;
        if (host.modelInput().mouseScrolled(mouseX, mouseY, amount, x, modelLabelY + LABEL_OFFSET, w, INPUT_HEIGHT)) {
            return;
        }

        y = afterThreeRowField(modelLabelY);
        y += sectionHeaderHeight(host.client());
        int searchProviderLabelY = y;
        y += FIELD_SPACING;
        int searchKeyLabelY = y;
        if (host.searchApiKeyInput().mouseScrolled(mouseX, mouseY, amount, x, searchKeyLabelY + LABEL_OFFSET, w, INPUT_HEIGHT)) {
            return;
        }

        y = afterThreeRowField(searchKeyLabelY);
        int googleCxLabelY = y;
        if (host.googleCseCxInput().mouseScrolled(mouseX, mouseY, amount, x, googleCxLabelY + LABEL_OFFSET, w, INPUT_HEIGHT)) {
            return;
        }

        int step = 12;
        int newScroll = (int) Math.round(host.scrollY() - amount * step);
        if (newScroll < 0) newScroll = 0;
        if (newScroll > host.maxScrollY()) newScroll = host.maxScrollY();
        host.setScrollY(newScroll);
    }
}
