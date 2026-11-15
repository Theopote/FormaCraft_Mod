package com.formacraft.config;

import com.formacraft.common.json.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SettingsConfigCommitTest {
    @TempDir Path temporary;

    @Test
    void editingAndDiscardingADraftDoesNotChangeCommittedSettings() {
        SettingsConfig before=SettingsConfig.INSTANCE.copy();
        SettingsConfig draft=SettingsConfig.INSTANCE.copy();
        draft.temperature=0.2f; draft.interactionReach=20; draft.showDebugWarnings=!before.showDebugWarnings;
        draft.autoStartBackend=!before.autoStartBackend; draft.apiKey="new-draft-key";
        assertEquals(before.temperature,SettingsConfig.INSTANCE.temperature);
        assertEquals(before.interactionReach,SettingsConfig.INSTANCE.interactionReach);
        assertEquals(before.showDebugWarnings,SettingsConfig.INSTANCE.showDebugWarnings);
        assertEquals(before.autoStartBackend,SettingsConfig.INSTANCE.autoStartBackend);
        assertEquals(before.apiKey,SettingsConfig.INSTANCE.apiKey);
    }

    @Test
    void failedReplacementPreservesBothThePreviousFileAndRuntime() throws Exception {
        SettingsConfig before=SettingsConfig.INSTANCE.copy(), draft=before.copy();
        draft.model="uncommitted-model";
        Path blocked=Files.createDirectory(temporary.resolve("blocked"));
        Path previous=Files.writeString(blocked.resolve("previous.json"),"keep this file");
        assertThrows(Exception.class,() -> SettingsConfig.commit(draft,blocked));
        assertEquals("keep this file",Files.readString(previous));
        assertEquals(before.model,SettingsConfig.INSTANCE.model);
        try(var files=Files.list(temporary)) { assertEquals(1,files.count()); }
    }

    @Test
    void successfulCommitPublishesTheSameCompleteSnapshotToDiskAndRuntime() throws Exception {
        SettingsConfig before=SettingsConfig.INSTANCE.copy();
        try {
            SettingsConfig draft=before.copy();
            draft.model="自定义/vendor-model"; draft.temperature=0.23f; draft.interactionReach=37;
            draft.searchApiKey="search-key"; draft.autoStartBackend=false;
            Path file=temporary.resolve("settings.json");
            Files.writeString(file,"previous contents");
            SettingsConfig.commit(draft,file);
            SettingsConfig saved=JsonUtil.get().fromJson(Files.readString(file),SettingsConfig.class);
            assertEquals(draft.model,saved.model);
            assertEquals(draft.model,SettingsConfig.INSTANCE.model);
            assertEquals(0.23f,SettingsConfig.INSTANCE.temperature);
            assertEquals(37,saved.interactionReach);
            assertFalse(saved.autoStartBackend);
            assertEquals("search-key",saved.searchApiKey);
        } finally { SettingsConfig.commit(before,temporary.resolve("restore.json")); }
    }
}
