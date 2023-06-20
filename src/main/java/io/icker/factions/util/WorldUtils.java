package io.icker.factions.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.util.Objects;

public class WorldUtils {
    public static MinecraftServer server;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register((server1 -> WorldUtils.server = server1));
    }

    public static boolean isValid(String level) {
        return WorldUtils.server.getWorldRegistryKeys().stream().anyMatch(key -> Objects.equals(key.getValue(), new Identifier(level)));
    }
}
