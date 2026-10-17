package dev.imb11.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

final class InventoryPanels {
    private static final ResourceLocation PANEL_SPRITE = ResourceLocation.withDefaultNamespace("recipe_book/overlay_recipe");
    private static final ResourceLocation INVENTORY_TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");

    private InventoryPanels() {
    }

    static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blitSprite(PANEL_SPRITE, x, y, width, height);
    }

    static void inset(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blit(INVENTORY_TEXTURE, x + 1, y + 1, width - 2, height - 2, 8, 84, 1, 1, 256, 256);
        insetBorder(graphics, x, y, width, height);
    }

    static void insetBorder(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blit(INVENTORY_TEXTURE, x, y, width - 1, 1, 8, 83, 1, 1, 256, 256);
        graphics.blit(INVENTORY_TEXTURE, x, y + 1, 1, height - 2, 7, 84, 1, 1, 256, 256);
        graphics.blit(INVENTORY_TEXTURE, x + width - 1, y + 1, 1, height - 1, 24, 84, 1, 1, 256, 256);
        graphics.blit(INVENTORY_TEXTURE, x, y + height - 1, width - 1, 1, 8, 100, 1, 1, 256, 256);
    }
}
