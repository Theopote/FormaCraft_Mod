package com.formacraft.server.command;

import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormaCraftCommandsRegistrationTest {
    @Test void confirmationCommandsSurviveWorldSwitchAndDispatcherRebuild() {
        MinecraftRegistryTestBootstrap.initialize();
        var first = new CommandDispatcher<ServerCommandSource>();
        var next = new CommandDispatcher<ServerCommandSource>();
        FormaCraftCommands.register(first);
        FormaCraftCommands.register(first);
        FormaCraftCommands.register(next);
        for (var dispatcher : java.util.List.of(first, next)) {
            assertNotNull(dispatcher.getRoot().getChild("forma_confirm"));
            assertNotNull(dispatcher.getRoot().getChild("forma_confirm").getChild("force"));
            assertNotNull(dispatcher.getRoot().getChild("forma_cancel"));
            assertNotNull(dispatcher.getRoot().getChild("formacraft_memory_commit"));
            assertNotNull(dispatcher.getRoot().getChild("formacraft_memory_commit").getChild("retry"));
            assertNotNull(dispatcher.getRoot().getChild("forma_assets").getChild("list"));
            assertNotNull(dispatcher.getRoot().getChild("forma_assets").getChild("preview").getChild("id"));
            assertNotNull(dispatcher.getRoot().getChild("forma_assets").getChild("restore").getChild("id"));
            for (String command : java.util.List.of("status","reload","disable"))
                assertNotNull(dispatcher.getRoot().getChild("forma_community").getChild(command));
            for(String command:java.util.List.of("export_recipe","export_lock","inspect","import"))
                assertNotNull(dispatcher.getRoot().getChild("forma_backup").getChild(command));
        }
    }
}
