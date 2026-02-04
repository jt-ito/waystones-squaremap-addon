package com.example.waystonesqmap.mixin;

import net.blay09.mods.waystones.client.gui.screen.WaystoneSelectionScreen;
import net.blay09.mods.waystones.menu.WaystoneSelectionMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.waystonesqmap.client.gui.WaystoneMapScreen;

@Mixin(Minecraft.class)
public class ScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof WaystoneSelectionScreen && !(screen instanceof WaystoneMapScreen)) {
            // Check if we should override (e.g. user didn't toggle it off)
            if (WaystoneMapScreen.showDefaultUI) {
                // Determine if we need to reset the flag immediately or if this is the "one-off" usage
                // Ideally, we reset it here so the *next* time it is opened, it goes back to our UI.
                WaystoneMapScreen.showDefaultUI = false;
                return;
            }

            if (!WaystoneMapScreen.showDefaultUI) {
                 WaystoneSelectionScreen waystoneScreen = (WaystoneSelectionScreen) screen;
                 // We need to access the menu/container. 
                 // WaystoneSelectionScreen extends WaystoneSelectionScreenBase which extends AbstractContainerScreen
                 if (waystoneScreen instanceof AbstractContainerScreen) {
                     AbstractContainerScreen<?> containerScreen = (AbstractContainerScreen<?>) waystoneScreen;
                     if (containerScreen.getMenu() instanceof WaystoneSelectionMenu) {
                         WaystoneSelectionMenu menu = (WaystoneSelectionMenu) containerScreen.getMenu();
                         ci.cancel();
                         // Open our custom screen
                         // We use 'null' for the title for now or copy it from the original screen if accessible
                         Minecraft.getInstance().setScreen(new WaystoneMapScreen(menu, Minecraft.getInstance().player.getInventory(), screen.getTitle()));
                     }
                 }
            }
        }
    }
}
