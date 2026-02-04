package com.example.waystonesqmap.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.util.Mth;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientMapManager {
    private static final int TILE_SIZE = 512;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, MapTile> TILES = new ConcurrentHashMap<>();
    private static final boolean DEBUG = false;
    
    private static Path cacheDir;
    private static String currentDimId;

    public static void init() {
        // Prepare base cache directory
        try {
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
            cacheDir = gameDir.resolve("waystones_sqmap_cache");
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void debug(String message) {
        if (DEBUG) {
            System.out.println("[WaystonesSQMap] " + message);
        }
    }

    private static String getContextId(Level level) {
        return getContextId(level.dimension());
    }

    public static String getContextId(net.minecraft.resources.ResourceKey<Level> dimKey) {
        String serverName = "singleplayer";
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) {
            serverName = mc.getCurrentServer().ip.replaceAll("[^a-zA-Z0-9.-]", "_");
        }
        String dim = dimKey.location().toString().replaceAll("[^a-zA-Z0-9.-]", "_");
        // Use underscores instead of File.separator to ensure safe ResourceLocation paths
        return serverName + "_" + dim;
    }

    public static MapTile getTile(Level level, int rX, int rZ) {
        if (cacheDir == null) init();
        
        String context = getContextId(level);
        String key = context + "_" + rX + "_" + rZ;
        return TILES.computeIfAbsent(key, k -> {
            MapTile tile = new MapTile(rX, rZ, context);
            tile.requestLoad(level); // Kick off load/gen
            return tile;
        });
    }

    public static MapTile getTileForContext(String context, int rX, int rZ) {
        if (cacheDir == null) init();

        String key = context + "_" + rX + "_" + rZ;
        return TILES.computeIfAbsent(key, k -> new MapTile(rX, rZ, context));
    }

    public static class MapTile implements AutoCloseable {
        private final int rX, rZ;
        private final String context;
        private DynamicTexture texture;
        private ResourceLocation resourceLocation;
        private final AtomicBoolean loaded = new AtomicBoolean(false);
        private final AtomicBoolean loading = new AtomicBoolean(false);
        private final AtomicBoolean empty = new AtomicBoolean(true);
        private final AtomicBoolean complete = new AtomicBoolean(false);
        private long lastUpdate = 0;

        public MapTile(int rX, int rZ, String context) {
            this.rX = rX;
            this.rZ = rZ;
            this.context = context;
        }

        public ResourceLocation getResourceLocation() {
            return loaded.get() ? resourceLocation : null;
        }

        public boolean isLoaded() {
            return loaded.get();
        }
        
        // Helper to check if chunks are likely loaded in this tile area
        private boolean hasLoadedChunks(Level level) {
             // 1. Check Center
             int startX = rX * TILE_SIZE;
             int startZ = rZ * TILE_SIZE;
             int centerCX = (startX >> 4) + 16;
             int centerCZ = (startZ >> 4) + 16;
             
             boolean centerLoaded = level.hasChunk(centerCX, centerCZ);
             if (centerLoaded) return true;

             // 2. Check Player Position (High priority: if player is in this tile, we MUST load)
             if (Minecraft.getInstance().player != null) {
                 int pX = Minecraft.getInstance().player.chunkPosition().x;
                 int pZ = Minecraft.getInstance().player.chunkPosition().z;
                 
                 // Tile bounds in chunk coords
                 int minCX = rX * 32;
                 int minCZ = rZ * 32;
                 int maxCX = minCX + 31;
                 int maxCZ = minCZ + 31;
                 
                 if (pX >= minCX && pX <= maxCX && pZ >= minCZ && pZ <= maxCZ) {
                     // Log that we found player, so we force load
                     // System.out.println("ClientMapManager: Player at " + pX + "," + pZ + " is inside tile " + rX + "," + rZ + ". Force load.");
                     return true;
                 } else {
                     // Debug logging for why it failed if we expected it to pass
                     // System.out.println("ClientMapManager: Player at " + pX + "," + pZ + " NOT inside " + rX + "," + rZ + " [" + minCX + "-" + maxCX + "]");
                 }
             }
             
             // 3. Fallback: Scan corners and midpoints?
             // If the player is viewing a map of a loaded area but standing elsewhere (e.g. map is zoomed out),
             // the center check might fail but other parts might be loaded.
             int[] offsets = {0, 31};
             for (int ox : offsets) {
                 for (int oz : offsets) {
                     if (level.hasChunk((startX >> 4) + ox, (startZ >> 4) + oz)) {
                         return true;
                     } 
                 }
             }
             
             return false;
        }

        public void requestLoad(Level level) {
            // Lock and Cooldown (3 seconds)
            if (!loading.compareAndSet(false, true)) return;
            if (System.currentTimeMillis() - lastUpdate < 3000 && loaded.get() && complete.get()) {
                debug("cooldown skip rX=" + rX + " rZ=" + rZ + " context=" + context);
                loading.set(false);
                return;
            }

            // Disk-only mode (no level available). Just load cached tiles if present.
            if (level == null) {
                debug("disk-only load rX=" + rX + " rZ=" + rZ + " context=" + context);
                lastUpdate = System.currentTimeMillis();
                EXECUTOR.submit(() -> {
                    try {
                        Path file = cacheDir.resolve(context).resolve(rX + "_" + rZ + ".png");
                        if (!Files.exists(file)) {
                            debug("disk-only miss rX=" + rX + " rZ=" + rZ + " file=" + file);
                            loading.set(false);
                            return;
                        }

                        NativeImage image = null;
                        try {
                            image = NativeImage.read(Files.newInputStream(file));
                        } catch (IOException e) {
                            System.err.println("ClientMapManager: Failed to read file " + file);
                            e.printStackTrace();
                        }

                        if (image != null) {
                            debug("disk-only hit rX=" + rX + " rZ=" + rZ + " file=" + file);
                            NativeImage finalImage = image;
                            RenderSystem.recordRenderCall(() -> {
                                try {
                                    if (this.texture != null) this.texture.close();
                                    if (this.resourceLocation != null) Minecraft.getInstance().getTextureManager().release(this.resourceLocation);

                                    this.texture = new DynamicTexture(finalImage);
                                    this.resourceLocation = Minecraft.getInstance().getTextureManager().register("sqmap_tile_" + context + "_" + rX + "_" + rZ, this.texture);
                                    this.empty.set(false);
                                    this.loaded.set(true);
                                    debug("disk-only upload rX=" + rX + " rZ=" + rZ + " rl=" + this.resourceLocation);
                                } finally {
                                    this.loading.set(false);
                                }
                            });
                        } else {
                            debug("disk-only read failed rX=" + rX + " rZ=" + rZ + " file=" + file);
                            loading.set(false);
                        }
                    } catch (Exception e) {
                        System.err.println("ClientMapManager: Fatal error in disk-only load");
                        e.printStackTrace();
                        loading.set(false);
                    }
                });
                return;
            }

            // If loaded and complete, we are done for this session unless force refreshed
            // If loaded but NOT complete (disk load or partial), we check if we can upgrade it
            if (loaded.get()) {
                if (complete.get()) {
                    loading.set(false);
                    return;
                }
                
                // Not complete (or loaded from disk). Check if chunks are available to improve it.
                boolean chunksAvailable = hasLoadedChunks(level);
                
                if (!chunksAvailable) {
                    loading.set(false);
                    return;
                }
                // Fallthrough to regenerate
            } else {
                // If not loaded yet, verify chunks before initial load 
                // ONLY if we plan to skip disk check? No, always check disk first.
            }
            
            lastUpdate = System.currentTimeMillis();
            debug("requestLoad start rX=" + rX + " rZ=" + rZ + " context=" + context + " dim=" + level.dimension().location() + " loaded=" + loaded.get() + " empty=" + empty.get());

            EXECUTOR.submit(() -> {
                try {
                    // 1. Try Disk (use as base so we don't wipe previously cached pixels)
                    Path file = cacheDir.resolve(context).resolve(rX + "_" + rZ + ".png");
                    NativeImage image = null;
                    boolean hadDiskImage = false;

                    boolean diskExists = Files.exists(file);
                    if (diskExists) {
                        try {
                            image = NativeImage.read(Files.newInputStream(file));
                            hadDiskImage = (image != null);
                            debug("disk load " + (hadDiskImage ? "hit" : "miss") + " rX=" + rX + " rZ=" + rZ + " file=" + file);
                        } catch (IOException e) {
                            System.err.println("ClientMapManager: Failed to read file " + file);
                            e.printStackTrace();
                        }
                    }

                    // If the file exists but couldn't be read, avoid overwriting to prevent data loss.
                    if (diskExists && !hadDiskImage) {
                        debug("disk read failed; skipping overwrite rX=" + rX + " rZ=" + rZ + " file=" + file);
                        loading.set(false);
                        return;
                    }

                    // 2. Generate / Merge
                    int scanResult = 0; // 0=Empty, 1=Partial, 2=Complete
                    boolean updatedFromWorld = false;

                    // If we have a disk image, draw on top of it to preserve prior data.
                    if (image == null) {
                        image = new NativeImage(TILE_SIZE, TILE_SIZE, true);
                    }

                    int newScanResult = generateFromWorld(image, level);
                    updatedFromWorld = true;
                    debug("generate result rX=" + rX + " rZ=" + rZ + " scan=" + newScanResult + " hadDisk=" + hadDiskImage);

                    // If there is no chunk data at all and no disk image, do not mark as loaded
                    if (!hadDiskImage && newScanResult == 0) {
                        debug("no data skip rX=" + rX + " rZ=" + rZ + " context=" + context);
                        image.close();
                        loading.set(false);
                        return;
                    }

                    // Safety: If we already had data and new generation is EMPTY, do not overwrite.
                    if (loaded.get() && !empty.get() && newScanResult == 0) {
                        debug("prevent overwrite with empty rX=" + rX + " rZ=" + rZ + " context=" + context);
                        image.close();
                        loading.set(false);
                        return;
                    }

                    scanResult = newScanResult;

                    final boolean hasData = (scanResult > 0) || hadDiskImage;
                    final boolean isComplete = (scanResult == 2);
                    final NativeImage finalImage = image;
                    // Save if we updated from the world and now have data (merge upgrades)
                    final boolean shouldSave = (finalImage != null && updatedFromWorld && hasData);
    
                    // 3. Upload
                    if (finalImage != null) {
                        RenderSystem.recordRenderCall(() -> {
                            try {
                                // Close old texture if refreshing
                                if (this.texture != null) this.texture.close();
                                if (this.resourceLocation != null) Minecraft.getInstance().getTextureManager().release(this.resourceLocation);
                                
                                this.texture = new DynamicTexture(finalImage);
                                this.resourceLocation = Minecraft.getInstance().getTextureManager().register("sqmap_tile_" + context + "_" + rX + "_" + rZ, this.texture);
                                
                                this.empty.set(!hasData);
                                if (isComplete) {
                                    this.complete.set(true);
                                }
                                this.loaded.set(true);
                                debug("upload rX=" + rX + " rZ=" + rZ + " hasData=" + hasData + " complete=" + isComplete + " rl=" + this.resourceLocation);
                            } catch (Exception e) {
                                System.err.println("ClientMapManager: Error uploading texture");
                                e.printStackTrace();
                            } finally {
                                this.loading.set(false);
                            }
                        });
    
                        if (shouldSave) {
                            try {
                                if (!Files.exists(file.getParent())) Files.createDirectories(file.getParent());
                                finalImage.writeToFile(file);
                                debug("saved rX=" + rX + " rZ=" + rZ + " file=" + file);
                            } catch (IOException e) {
                                System.err.println("ClientMapManager: Failed to write file " + file);
                                e.printStackTrace();
                            }
                        }
                    } else {
                         debug("finalImage null rX=" + rX + " rZ=" + rZ + " context=" + context);
                         loading.set(false);
                    }
                } catch (Exception e) {
                    System.err.println("ClientMapManager: Fatal error in executor task");
                    e.printStackTrace();
                    loading.set(false);
                }
            });
        }
        
        private int generateFromWorld(NativeImage image, Level level) {
            int loadedChunks = 0;
            int totalChunks = 0;
            boolean hasData = false;
            int startX = rX * TILE_SIZE;
            int startZ = rZ * TILE_SIZE;

            // Iterate chunks (32x32 chunks)
            for (int cX = 0; cX < 32; cX++) {
                for (int cZ = 0; cZ < 32; cZ++) {
                    totalChunks++;
                    int chunkX = (startX >> 4) + cX;
                    int chunkZ = (startZ >> 4) + cZ;

                    ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                    if (chunk == null) continue;

                    loadedChunks++;
                    hasData = true;
                    int blockBaseX = startX + (cX << 4);
                    int blockBaseZ = startZ + (cZ << 4);

                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int worldX = blockBaseX + x;
                            int worldZ = blockBaseZ + z;

                            int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                            if (height <= level.getMinBuildHeight()) continue;

                            BlockPos pos = new BlockPos(worldX, height - 1, worldZ);
                            net.minecraft.world.level.block.state.BlockState state = chunk.getBlockState(pos);
                            MapColor color = state.getMapColor(level, pos);

                            if (color != MapColor.NONE && color.col != 0) {
                                int rgba = color.col;
                                int r = (rgba >> 16) & 0xFF;
                                int g = (rgba >> 8) & 0xFF;
                                int b = rgba & 0xFF;
                                int a = 255;
                                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                                image.setPixelRGBA((cX << 4) + x, (cZ << 4) + z, abgr);
                            }
                        }
                    }
                }
            }
            if (!hasData) return 0; // Empty
            if (loadedChunks < totalChunks) return 1; // Partial
            return 2; // Complete
        }

        @Override
        public void close() {
            if (resourceLocation != null) {
                Minecraft.getInstance().getTextureManager().release(resourceLocation);
            }
            if (texture != null) {
                texture.close();
            }
        }
    }
}
