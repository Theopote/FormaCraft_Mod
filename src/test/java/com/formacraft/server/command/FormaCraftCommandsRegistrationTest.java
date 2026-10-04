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
        }
    }
}
