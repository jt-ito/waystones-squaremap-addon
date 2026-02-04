package com.example.waystonesqmap.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

public class WaystonesSquaremapAddonClient implements ClientModInitializer {
    private static int tickCounter = 0;
    private static int lastTileX = Integer.MIN_VALUE;
    private static int lastTileZ = Integer.MIN_VALUE;
    private static int lastRenderDistance = -1;
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.level == null) return;

            // Throttle to once per second to avoid heavy scans
            if ((tickCounter++ % 20) != 0) return;

            Level level = client.level;
            int tileX = Math.floorDiv(Mth.floor(client.player.getX()), 512);
            int tileZ = Math.floorDiv(Mth.floor(client.player.getZ()), 512);

            // Map all tiles within render distance (squaremap-like: only loaded chunks are used by generator)
            int renderDistance = client.options.renderDistance().get();
            int tileRadius = Math.max(1, (int) Math.ceil(renderDistance / 32.0));

            // Only recalc when player enters a new tile or render distance changes
            if (tileX == lastTileX && tileZ == lastTileZ && renderDistance == lastRenderDistance) {
                return;
            }
            lastTileX = tileX;
            lastTileZ = tileZ;
            lastRenderDistance = renderDistance;

            for (int dx = -tileRadius; dx <= tileRadius; dx++) {
                for (int dz = -tileRadius; dz <= tileRadius; dz++) {
                    ClientMapManager.getTile(level, tileX + dx, tileZ + dz).requestLoad(level);
                }
            }
        });
    }
}
