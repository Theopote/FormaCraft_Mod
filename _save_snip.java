    private boolean canSaveWithoutHealthCheck() {
        var st = ComponentTool.INSTANCE.getState();
        if (!com.formacraft.client.tool.ComponentCaptureTypes.ready(st) || st.isCaptureSavePending()) return false;
        if (!selectionController.hasValidSelection(client)) {
            return false;
        }
        if (st.name == null || st.name.isBlank()) {
            return false;
        }
        if (st.captureDraft.anchor.worldPos == null) {
            return false;
        }
        BlockPos anchor = st.captureDraft.anchor.worldPos;
        return selectionController.isAnchorLocationAllowed(anchor);
    }

    private boolean canSave() {
        return canSaveWithoutHealthCheck() && !healthCoordinator.blocksSave();
    }

    private void saveComponent() {
        if (!canSave()) {
            HudToast.show("淇濆瓨澶辫触锛氳妫€鏌ラ€夊尯鍜屽悕绉?, true);
            return;
        }

        var st = ComponentTool.INSTANCE.getState();
        var health = healthCoordinator.check();
        if (health.hasErrors()) {
            HudToast.show("淇濆瓨澶辫触锛氬瓨鍦ㄩ敊璇」锛堣鍏堜慨澶嶏級", true);
            return;
        }
        if (health.hasWarnings()) {
            HudToast.show("瀛樺湪璀﹀憡锛氫粛鍙繚瀛橈紝浣嗗彲鑳藉奖鍝嶆斁缃晥鏋?);
        }
        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client, ComponentTool.INSTANCE.getState().captureDraft);
        if (json == null || json.isBlank()) {
            HudToast.show("淇濆瓨澶辫触锛氳妫€鏌ラ€夊尯", true);
            return;
        }

        ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
        String ruleError = com.formacraft.common.component.variant.CapturedComponentRuleValidator.validate(def);
        if (ruleError != null) {
            HudToast.show("淇濆瓨澶辫触锛? + ruleError, true);
            return;
        }
        var saveOutcome = healthCoordinator.prepareForSave(client, json);
        if (saveOutcome.blocked()) {
            HudToast.show(saveOutcome.blockToast(), true);
            return;
        }
        json = saveOutcome.json();
        def = saveOutcome.definition();
        if (saveOutcome.warnToast() != null) {
            HudToast.show(saveOutcome.warnToast());
        }

        // 鐢熸垚缂╃暐鍥撅紙浣跨敤淇鍚庣殑 def锛屽鏋?def 涓?null 鍒欓噸鏂拌В鏋愶級
        byte[] thumbnailPng = null;
        try {
            if (def == null) {
                def = JsonUtil.fromJson(json, ComponentDefinition.class);
            }
            thumbnailPng = thumbnailService.encodePng(def);
        } catch (Throwable t) {
            com.formacraft.FormacraftMod.LOGGER.warn("Failed to generate thumbnail", t);
        }

        // 绛夊緟鏈嶅姟绔?ComponentSaveAck锛沜atalog 浠呭埛鏂扮洰褰曟暟鎹?        String componentName = st.name.trim();
        ComponentTool.INSTANCE.markSavePending(componentName);
        HudToast.show("姝ｅ湪淇濆瓨鏋勪欢銆? + componentName + "銆嶁€?);
        try { FormaCraftClientNetworking.sendSaveComponent(json, thumbnailPng); }
        catch (Exception failedSend) {
            com.formacraft.FormacraftMod.LOGGER.warn("Capture upload failed", failedSend);
            ComponentTool.INSTANCE.onSaveAckFromServer("", componentName, false, "淇濆瓨鏋勪欢澶辫触锛? + failedSend.getMessage());
        }
    }