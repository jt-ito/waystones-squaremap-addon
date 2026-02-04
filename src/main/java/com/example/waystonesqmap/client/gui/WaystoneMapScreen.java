package com.example.waystonesqmap.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.menu.WaystoneSelectionMenu;
import net.blay09.mods.waystones.network.message.SelectWaystoneMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.waystonesqmap.client.FavoritesManager;
import net.blay09.mods.balm.api.Balm;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.level.material.MapColor;
import com.mojang.blaze3d.platform.NativeImage;

import net.blay09.mods.waystones.client.gui.screen.WaystoneSelectionScreen; // Added import

// Open Parties and Claims Imports
import xaero.pac.client.api.OpenPACClientAPI;
import xaero.pac.client.parties.party.api.IClientPartyAPI;
import xaero.pac.common.parties.party.member.api.IPartyMemberAPI;
import xaero.pac.common.parties.party.api.IPartyMemberDynamicInfoSyncableAPI;

import com.example.waystonesqmap.client.ClientMapManager;

public class WaystoneMapScreen extends AbstractContainerScreen<WaystoneSelectionMenu> {

    public static boolean showDefaultUI = false;

    private final List<Waystone> allWaystones;
    private List<Waystone> filteredWaystones;
    private ResourceKey<Level> currentDimensionFilter = Level.OVERWORLD;

    private FavoritesList favoritesList;
    private Waystone selectedWaystone;

    // Map View Settings
    private double mapCenterX, mapCenterZ;
    private double mapZoom = 1.0;
    private int mapX, mapY, mapW, mapH;
    private boolean isDraggingMap = false;
    private double lastMouseX, lastMouseY;

    // Player tracking to detect teleports/dimension swaps
    private double lastTrackedPlayerX;
    private double lastTrackedPlayerZ;
    private boolean hasTrackedPlayerPos = false;
    private ResourceKey<Level> lastTrackedDimension = null;

    // Tile Cache Handled by ClientMapManager now
    private static final int TILE_SIZE = 512;

    public WaystoneMapScreen(WaystoneSelectionMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.allWaystones = new ArrayList<>(menu.getWaystones());
        this.filteredWaystones = new ArrayList<>(allWaystones);
        
        // Initialize map center to player position
        if (Minecraft.getInstance().player != null) {
            this.mapCenterX = Minecraft.getInstance().player.getX();
            this.mapCenterZ = Minecraft.getInstance().player.getZ();
        }
    }

    @Override
    protected void init() {
        super.init();
        
        this.leftPos = 0;
        this.topPos = 0;
        this.imageWidth = width;
        this.imageHeight = height;

        // UI Layout
        int sidebarWidth = 100;
        mapX = sidebarWidth;
        mapY = 0;
        mapW = width - sidebarWidth;
        mapH = height;

        // Favorites List
        // Note: ObjectSelectionList constructor args vary by MC version sometimes. 
        // Typically: mc, width, height, top, bottom, itemHeight
        // The list handles its own "left" and "right" internally usually centered or filling width.
        // We need to ensure it knows it is strictly 0 to 100 on X axis.
        int itemHeight = 20;
        favoritesList = new FavoritesList(this.minecraft, sidebarWidth, height, 40, itemHeight);
        favoritesList.setX(0); // Explicitly set X to 0 (Left side)
        // Some versions use setLeftPos, handled via setX in updated mappings
        // If the list logic centers itself, we might have issues. 
        // Let's try to override the row width to be smaller too.
        
        this.addRenderableWidget(favoritesList);

        // Default to the player's current dimension when opening the screen
        if (Minecraft.getInstance().player != null) {
            this.currentDimensionFilter = Minecraft.getInstance().player.level().dimension();
        }

        updateFilter();

        // Dimension Toggle Buttons
        int btnW = 30;
        this.addRenderableWidget(Button.builder(Component.literal("OW"), b -> setDimension(Level.OVERWORLD))
                .pos(5, 5).size(btnW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Nether"), b -> setDimension(Level.NETHER))
                .pos(35, 5).size(btnW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("End"), b -> setDimension(Level.END))
                .pos(65, 5).size(btnW, 20).build());

        // Toggle Default UI Button
        this.addRenderableWidget(Button.builder(Component.literal("Default UI"), b -> {
            showDefaultUI = true;
            this.minecraft.setScreen(new WaystoneSelectionScreen(this.menu, this.minecraft.player.getInventory(), this.title));
        }).pos(width - 80, 5).size(75, 20).build());
    }
    
    private void setDimension(ResourceKey<Level> dim) {
        this.currentDimensionFilter = dim;
        updateFilter();
    }

    private void updateFilter() {
        filteredWaystones = allWaystones.stream()
                .filter(w -> w.getDimension().equals(currentDimensionFilter))
                .collect(Collectors.toList());
        favoritesList.refresh();

        // Recenter to nearest waystone in the selected dimension
        if (!filteredWaystones.isEmpty()) {
            double px = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getX() : 0;
            double pz = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getZ() : 0;

            Waystone nearest = filteredWaystones.stream()
                    .min(Comparator.comparingDouble(w -> {
                        BlockPos pos = w.getPos();
                        double dx = pos.getX() - px;
                        double dz = pos.getZ() - pz;
                        return dx * dx + dz * dz;
                    }))
                    .orElse(null);

            if (nearest != null) {
                mapCenterX = nearest.getPos().getX();
                mapCenterZ = nearest.getPos().getZ();
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        
        // Render Map Background
        renderMap(guiGraphics, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        // Removed manual favoritesList.render - let super handle it via widget list
        // this.favoritesList.render(guiGraphics, mouseX, mouseY, partialTick);
        
        // Render Waystone Icons on Map
        renderWaystonesOnMap(guiGraphics, mouseX, mouseY);

        // Render Party Members
        renderPartyMembers(guiGraphics, mouseX, mouseY);
    }
    
    private void renderPartyMembers(GuiGraphics gfx, int mx, int my) {
        if (Minecraft.getInstance().player == null) return;
        
        try {
            IClientPartyAPI party = OpenPACClientAPI.get().getClientPartyStorage().getParty();
            if (party == null) return;

            var dynamicStorage = OpenPACClientAPI.get().getClientPartyStorage().getPartyMemberDynamicInfoSyncableStorage();
            if (dynamicStorage == null) return;

            gfx.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);

            party.getMemberInfoStream().forEach(member -> {
                if (member == null || member.getUUID() == null) return; // Defensive check
                if (member.getUUID().equals(Minecraft.getInstance().player.getUUID())) return; // Skip self if desired, or render differently

                IPartyMemberDynamicInfoSyncableAPI info = dynamicStorage.getForPlayer(member.getUUID());
                if (info != null) {
                    ResourceLocation memberDim = info.getDimension();
                    // Check if member is in the same dimension as the currently viewed map dimension
                    if (memberDim != null && memberDim.equals(currentDimensionFilter.location())) {
                        double screenX = mapX + mapW / 2.0 + (info.getX() - mapCenterX) * mapZoom;
                        double screenY = mapY + mapH / 2.0 + (info.getZ() - mapCenterZ) * mapZoom;

                        if (screenX > mapX && screenX < mapX + mapW && screenY > mapY && screenY < mapY + mapH) {
                            // Draw Player Head
                            int headSize = 8;
                            int x = (int)screenX - headSize / 2;
                            int y = (int)screenY - headSize / 2;

                            ResourceLocation skinLocation = DefaultPlayerSkin.getDefaultTexture();
                            try {
                                if (Minecraft.getInstance().getConnection() != null) {
                                    PlayerInfo pInfo = Minecraft.getInstance().getConnection().getPlayerInfo(member.getUUID());
                                    if (pInfo != null) {
                                        skinLocation = pInfo.getSkin().texture();
                                    } else {
                                         // Fallback if player info is missing (e.g. offline or not tracked by client connection yet)
                                         skinLocation = Minecraft.getInstance().getSkinManager().getInsecureSkin(new GameProfile(member.getUUID(), member.getUsername())).texture();
                                    }
                                }
                            } catch (Exception ignored) {}

                            // Render Face
                            RenderSystem.enableBlend();
                            gfx.blit(skinLocation, x, y, headSize, headSize, 8.0F, 8.0F, 8, 8, 64, 64);
                            // Render Hat Layer
                            gfx.blit(skinLocation, x, y, headSize, headSize, 40.0F, 8.0F, 8, 8, 64, 64);
                            RenderSystem.disableBlend();

                            // Render Name Tag
                            Component name = Component.literal(member.getUsername());
                            int nameWidth = font.width(name);
                            // Draw centered name above head
                            gfx.drawString(font, name, (int)screenX - nameWidth / 2, (int)screenY - headSize - 4, 0xFFFFFFFF, false);
                        }
                    }
                }
            });
            
            gfx.disableScissor();
        } catch (NoClassDefFoundError | Exception e) {
            // Soft fail if API is missing or something goes wrong
        }
    }
    
    private void renderMap(GuiGraphics gfx, int mx, int my) {
        // Draw Sidebar Background
        gfx.fill(0, 0, mapX, height, 0xFF000000); // Black sidebar background

        // Draw Map Viewport Background (Void color)
        gfx.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF121212); 
        
        gfx.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            boolean viewingCurrentDim = level.dimension().equals(currentDimensionFilter);
            Level levelForGen = viewingCurrentDim ? level : null;

            // Determine visible region
            // Visible Bounds in World Coords
            double viewW = mapW / mapZoom;
            double viewH = mapH / mapZoom;
            double leftWorld = mapCenterX - viewW / 2.0;
            double topWorld = mapCenterZ - viewH / 2.0;
            double rightWorld = leftWorld + viewW;
            double bottomWorld = topWorld + viewH;

            int minRgX = Math.floorDiv(Mth.floor(leftWorld), TILE_SIZE);
            int minRgZ = Math.floorDiv(Mth.floor(topWorld), TILE_SIZE);
            int maxRgX = Math.floorDiv(Mth.floor(rightWorld), TILE_SIZE);
            int maxRgZ = Math.floorDiv(Mth.floor(bottomWorld), TILE_SIZE);

            // Always request the tile under the player to ensure new areas are cached
            if (Minecraft.getInstance().player != null) {
                int pTileX = Math.floorDiv(Mth.floor(Minecraft.getInstance().player.getX()), TILE_SIZE);
                int pTileZ = Math.floorDiv(Mth.floor(Minecraft.getInstance().player.getZ()), TILE_SIZE);
                ClientMapManager.getTile(level, pTileX, pTileZ).requestLoad(level);
            }

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            String context = ClientMapManager.getContextId(currentDimensionFilter);

            for (int rX = minRgX; rX <= maxRgX; rX++) {
                for (int rZ = minRgZ; rZ <= maxRgZ; rZ++) {
                    ClientMapManager.MapTile tile = ClientMapManager.getTileForContext(context, rX, rZ);
                    
                    // Request update if needed (e.g. came into range)
                    tile.requestLoad(levelForGen); 

                    if (tile.isLoaded() && tile.getResourceLocation() != null) {
                        // Draw Tile
                        // Tile World Pos
                        double tileWorldX = rX * TILE_SIZE;
                        double tileWorldZ = rZ * TILE_SIZE;
                        
                        double screenX = mapX + mapW / 2.0 + (tileWorldX - mapCenterX) * mapZoom;
                        double screenZ = mapY + mapH / 2.0 + (tileWorldZ - mapCenterZ) * mapZoom;
                        double screenSize = TILE_SIZE * mapZoom;
                        
                        // Blit
                        RenderSystem.setShaderTexture(0, tile.getResourceLocation());
                        gfx.blit(tile.getResourceLocation(), (int)screenX, (int)screenZ, (int)Math.ceil(screenSize), (int)Math.ceil(screenSize), 0, 0, TILE_SIZE, TILE_SIZE, TILE_SIZE, TILE_SIZE);
                    }
                }
            }
        }
        
        gfx.disableScissor();
        RenderSystem.disableBlend();
    }

    private void renderWaystonesOnMap(GuiGraphics gfx, int mx, int my) {
        // Convert world coords to screen coords
        gfx.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        
        for (Waystone w : filteredWaystones) {
            BlockPos pos = w.getPos();
            
            // Simple projection: (worldPos - center) * scale + screenCenter
            double screenX = mapX + mapW / 2.0 + (pos.getX() - mapCenterX) * mapZoom;
            double screenY = mapY + mapH / 2.0 + (pos.getZ() - mapCenterZ) * mapZoom; // 2D map usually mostly X/Z
            
            if (screenX > mapX && screenX < mapX + mapW && screenY > mapY && screenY < mapY + mapH) {
                 boolean isFav = FavoritesManager.contains(w.getWaystoneUid());
                 int color = isFav ? 0xFFFFD700 : 0xFFAAAAAA; // Gold or Light Gray
                 int size = (int)(6 + (mapZoom > 2 ? 3 : 0)); // Increased size
                 
                 // Render Waystone Icons (using a simple texture or shape)
                 // Let's draw a small obelisk shape
                 gfx.fill((int)screenX - 2, (int)screenY - size, (int)screenX + 2, (int)screenY, color); // Thicker Pillar
                 gfx.fill((int)screenX - 3, (int)screenY, (int)screenX + 3, (int)screenY + 2, color); // Bigger Base
                 
                 if (mx >= screenX - 6 && mx <= screenX + 6 && my >= screenY - size - 2 && my <= screenY + 2) {
                     gfx.renderTooltip(font, w.getName(), (int)screenX, (int)screenY);
                 }
            }
        }
        gfx.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        
        // 1. Manually check widgets (Buttons, Lists)
        for (GuiEventListener child : this.children()) {
             // Only allow clicking widgets if they are NOT covered by the map (unless they are the map widgets?)
             // Actually, widgets are clearly defined (sidebar or top buttons).
             // However, ensure the list doesn't steal map clicks if overlapping (it shouldn't).
            if (child.isMouseOver(mouseX, mouseY) && child.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(child);
                if (button == 0) {
                    this.setDragging(true);
                }
                return true;
            }
        }
        
        // 2. Map Logic & Dragging check
        if (button == 0 && mouseX >= mapX && mouseX <= mapX + mapW && mouseY >= mapY && mouseY <= mapY + mapH) {
             // Check if we clicked on a waystone first
             for (Waystone w : filteredWaystones) {
                BlockPos pos = w.getPos();
                double screenX = mapX + mapW / 2.0 + (pos.getX() - mapCenterX) * mapZoom;
                double screenY = mapY + mapH / 2.0 + (pos.getZ() - mapCenterZ) * mapZoom;
                
                int hitBoxSize = 10;
                // Check simple bounding box centered on the anchor point (screenX, screenY)
                if (Math.abs(mouseX - screenX) < hitBoxSize && mouseY >= screenY - hitBoxSize * 1.5 && mouseY <= screenY + 2) {
                    if (Screen.hasShiftDown()) {
                        toggleFavorite(w);
                        return true;
                    } else {
                        selectWaystone(w);
                        return true;
                    }
                }
            }
            
            // If no waystone clicked, start dragging
            isDraggingMap = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDraggingMap = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDraggingMap) {
            // Drag inverted to move camera
            mapCenterX -= dragX / mapZoom;
            mapCenterZ -= dragY / mapZoom;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= mapX && mouseX <= mapX + mapW && mouseY >= mapY && mouseY <= mapY + mapH) {
            double zoomFactor = 1.1;
            if (scrollY > 0) {
                mapZoom *= zoomFactor;
            } else if (scrollY < 0) {
                mapZoom /= zoomFactor;
            }
            // Clamp Zoom
            mapZoom = Math.max(0.1, Math.min(mapZoom, 10.0));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    
    private boolean pendingRefresh = false;

    private void toggleFavorite(Waystone w) {
        if (FavoritesManager.contains(w.getWaystoneUid())) {
            FavoritesManager.remove(w.getWaystoneUid());
        } else {
            FavoritesManager.add(w.getWaystoneUid());
        }
        // Defer refresh to avoid ConcurrentModificationException during rendering/event loop
        this.pendingRefresh = true;
    }
    
    @Override
    public void containerTick() {
        super.containerTick();
        if (this.pendingRefresh) {
            this.pendingRefresh = false;
            favoritesList.refresh();
        }

        // Recenter on teleport or dimension change so new areas are mapped and visible
        if (Minecraft.getInstance().player != null) {
            double px = Minecraft.getInstance().player.getX();
            double pz = Minecraft.getInstance().player.getZ();
            ResourceKey<Level> dim = Minecraft.getInstance().player.level().dimension();

            boolean dimChanged = (lastTrackedDimension == null || !lastTrackedDimension.equals(dim));
            if (!hasTrackedPlayerPos || dimChanged) {
                mapCenterX = px;
                mapCenterZ = pz;
                hasTrackedPlayerPos = true;
                lastTrackedDimension = dim;
                isDraggingMap = false;
            } else {
                double dx = px - lastTrackedPlayerX;
                double dz = pz - lastTrackedPlayerZ;
                double distSq = dx * dx + dz * dz;
                if (distSq > 256.0 * 256.0) { // large jump = teleport
                    mapCenterX = px;
                    mapCenterZ = pz;
                    isDraggingMap = false;
                }
            }

            lastTrackedPlayerX = px;
            lastTrackedPlayerZ = pz;
        }
    }
    
    private void selectWaystone(Waystone w) {
        // Ensure map view matches the selected waystone's dimension
        if (!w.getDimension().equals(currentDimensionFilter)) {
            setDimension(w.getDimension());
            mapCenterX = w.getPos().getX();
            mapCenterZ = w.getPos().getZ();
        }

        // Method 1: Send Packet
        try {
            Balm.getNetworking().sendToServer(new SelectWaystoneMessage(w.getWaystoneUid()));
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Method 2: Try Menu Click (Fallback - Standard Container Menu behavior)
        // Waystones often uses the index in the list as the 'button' id for simple selection
        int index = -1;
        int i = 0;
        for (Waystone element : this.menu.getWaystones()) {
            if (element.getWaystoneUid().equals(w.getWaystoneUid())) {
                index = i;
                break;
            }
            i++;
        }

        if (index >= 0) {
             if (this.minecraft != null && this.minecraft.gameMode != null) {
                 this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, index);
             }
        }
        
        // Do NOT close immediately. Let the server teleport logic close the screen.
        // this.onClose();
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int i, int j) {
        // Disable default label rendering
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float f, int i, int j) {
        // Handled in render()
    }
    
    // -- Inner Classes --

    class FavoritesList extends ObjectSelectionList<FavoriteEntry> {
        public FavoritesList(Minecraft mc, int width, int height, int top, int itemHeight) {
            super(mc, width, height, top, itemHeight);
        }
        
        public int getRowLeftPublic() {
            return this.getRowLeft();
        }

        @Override
        public int getRowWidth() {
            return 90; // Just slightly less than the 100 sidebar width
        }

        @Override
        protected int getScrollbarPosition() {
            return 95; // Push scrollbar to right edge of sidebar
        }
        
        public void refresh() {
            this.clearEntries();
            List<UUID> favIds = FavoritesManager.getFavorites();
            
            // Create a lookup for current available waystones
            Map<UUID, Waystone> available = new HashMap<>();
            for (Waystone w : filteredWaystones) {
                available.put(w.getWaystoneUid(), w);
            }
            
            // Add entries in the order of the Favorites List
            for (UUID id : favIds) {
                if (available.containsKey(id)) {
                    this.addEntry(new FavoriteEntry(available.get(id)));
                }
            }
        }
    }
    
    class FavoriteEntry extends ObjectSelectionList.Entry<FavoriteEntry> {
        private final Waystone waystone;
        public FavoriteEntry(Waystone w) { this.waystone = w; }
        
        @Override
        public Component getNarration() { return waystone.getName(); }
        
        @Override
        public void render(GuiGraphics gfx, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
            // Draw background highlight if hovered
            if (hovered) {
                gfx.fill(left - 2, top - 2, left + width + 2, top + height + 2, 0x20FFFFFF);
            }

            // Render text
            String name = waystone.getName().getString();
            if (font.width(name) > 65) {
               name = font.substrByWidth(waystone.getName(), 60).getString() + "...";
            }
            // Use yellow for text if hovered, otherwise white
            int textColor = hovered ? 0xFFFFFF55 : 0xFFFFFFFF;
            gfx.drawString(font, name, left + 2, top + 5, textColor, false);
            
            // Arrows
            boolean hoverUp = mouseX >= left + 70 && mouseX < left + 78 && mouseY >= top + 2 && mouseY < top + 10;
            boolean hoverDown = mouseX >= left + 80 && mouseX < left + 88 && mouseY >= top + 2 && mouseY < top + 10;
            
            int colorUp = hoverUp ? 0xFFFFFF55 : 0xFFAAAAAA; // Yellow if hover, Gray otherwise
            int colorDown = hoverDown ? 0xFFFFFF55 : 0xFFAAAAAA;
            
            // Draw Up Arrow (Triangle)
            // Center roughly at left+74, top+6
            gfx.fill(left + 74, top + 3, left + 75, top + 4, colorUp); // Tip
            gfx.fill(left + 73, top + 4, left + 76, top + 5, colorUp); 
            gfx.fill(left + 72, top + 5, left + 77, top + 6, colorUp); // Base
            
            // Draw Down Arrow (Triangle)
            // Center roughly at left+84, top+6
            gfx.fill(left + 82, top + 3, left + 87, top + 4, colorDown); // Base
            gfx.fill(left + 83, top + 4, left + 86, top + 5, colorDown); 
            gfx.fill(left + 84, top + 5, left + 85, top + 6, colorDown); // Tip
        }
        
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
             // We are guaranteed to be in the row if this is called by the list
             int left = favoritesList.getRowLeftPublic(); 
             
             // X-Check for buttons:
             if (button == 0) {
                 if (mouseX >= left + 70 && mouseX < left + 78) {
                     // Up
                     FavoritesManager.moveUp(waystone.getWaystoneUid());
                     // Force list refresh next tick
                     WaystoneMapScreen.this.pendingRefresh = true;
                     return true;
                 }
                 if (mouseX >= left + 80 && mouseX < left + 88) {
                     // Down
                     FavoritesManager.moveDown(waystone.getWaystoneUid());
                     WaystoneMapScreen.this.pendingRefresh = true;
                     return true;
                 }
                 
                 if (Screen.hasShiftDown()) {
                     toggleFavorite(waystone);
                 } else {
                     selectWaystone(waystone);
                 }
                 return true;
             }
             return false;
        }
    }
}
