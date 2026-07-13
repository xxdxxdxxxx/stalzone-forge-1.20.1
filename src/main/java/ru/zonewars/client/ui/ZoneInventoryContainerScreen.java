package ru.zonewars.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import ru.zonewars.client.net.ZoneWarsNetworking;
import ru.zonewars.forge.menu.ZoneInventoryMenu;

import java.util.ArrayList;
import java.util.List;

public final class ZoneInventoryContainerScreen extends AbstractContainerScreen<ZoneInventoryMenu> {
    private static final int BACKDROP = 0xD8080C10;
    private static final int PANEL = 0xF20D1217;
    private static final int SLOT_BG = 0xFF11171C;
    private static final int SLOT_HOVER = 0xFF182630;
    private static final int STROKE = 0xFF46545E;
    private static final int TEXT = 0xFFEAF0F4;
    private static final int MUTED = 0xFF8FA0AC;
    private static final int BLUE = 0xFF65B2FF;
    private static final int YELLOW = 0xFFE6C766;

    private static final int SHOP_WIDTH = 136;
    private static final int SHOP_GAP = 12;

    private static final List<ShopEntry> SHOP = List.of(
        new ShopEntry("ak101", "AK-101 FIELD KIT", Items.CROSSBOW, 650),
        new ShopEntry("ak103", "AK-103 FIELD KIT", Items.CROSSBOW, 700),
        new ShopEntry("pkp", "PKP SUPPORT KIT", Items.CROSSBOW, 1000),
        new ShopEntry("ammo", "AMMUNITION", Items.ARROW, 100),
        new ShopEntry("medkit", "MEDKIT", Items.GOLDEN_APPLE, 150),
        new ShopEntry("tent", "FIELD TENT", Items.CAMPFIRE, 500),
        new ShopEntry("outpost", "SQUAD OUTPOST", Items.LODESTONE, 1000)
    );

    private final List<ShopTarget> shopTargets = new ArrayList<>();
    private int selectedShop = -1;

    public ZoneInventoryContainerScreen(ZoneInventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - (imageWidth + SHOP_WIDTH + SHOP_GAP)) / 2 + SHOP_WIDTH + SHOP_GAP;
        topPos = (height - imageHeight) / 2;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(leftPos - 8, topPos - 24, leftPos + imageWidth + 8, topPos + imageHeight + 8, PANEL);
        graphics.renderOutline(leftPos - 8, topPos - 24, imageWidth + 16, imageHeight + 32, STROKE);
        graphics.drawString(font, "INVENTORY", leftPos, topPos - 16, TEXT, false);
        for (Slot slot : menu.slots) {
            int sx = leftPos + slot.x - 1;
            int sy = topPos + slot.y - 1;
            boolean hover = mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18;
            graphics.fill(sx, sy, sx + 18, sy + 18, hover ? SLOT_HOVER : SLOT_BG);
            graphics.renderOutline(sx, sy, 18, 18, STROKE);
        }
        drawShop(graphics, leftPos - SHOP_WIDTH - SHOP_GAP, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    private void drawShop(GuiGraphics graphics, int shopX, int mouseX, int mouseY) {
        shopTargets.clear();
        int shopY = topPos - 24;
        int shopHeight = imageHeight + 32;
        graphics.fill(shopX, shopY, shopX + SHOP_WIDTH, shopY + shopHeight, PANEL);
        graphics.renderOutline(shopX, shopY, SHOP_WIDTH, shopHeight, STROKE);
        graphics.drawString(font, "FIELD MERCHANT", shopX + 8, shopY + 8, TEXT, false);
        int slot = 38;
        int gap = 3;
        int startX = shopX + 8;
        int startY = shopY + 24;
        for (int index = 0; index < SHOP.size(); index++) {
            int column = index % 3;
            int row = index / 3;
            int sx = startX + column * (slot + gap);
            int sy = startY + row * (slot + gap);
            boolean hover = inside(mouseX, mouseY, sx, sy, slot, slot);
            graphics.fill(sx, sy, sx + slot, sy + slot, hover || selectedShop == index ? SLOT_HOVER : SLOT_BG);
            graphics.renderOutline(sx, sy, slot, slot, selectedShop == index ? BLUE : STROKE);
            graphics.renderItem(new ItemStack(SHOP.get(index).item()), sx + 11, sy + 6);
            graphics.drawString(font, String.valueOf(SHOP.get(index).price()), sx + 3, sy + 28, YELLOW, false);
            shopTargets.add(new ShopTarget(index, sx, sy, slot, slot));
        }
        int detailsY = startY + 3 * (slot + gap) + 6;
        if (selectedShop >= 0 && selectedShop < SHOP.size()) {
            ShopEntry entry = SHOP.get(selectedShop);
            graphics.drawString(font, entry.name(), shopX + 8, detailsY, BLUE, false);
            graphics.drawString(font, "Price: " + entry.price(), shopX + 8, detailsY + 12, YELLOW, false);
            graphics.drawString(font, "Click again to buy", shopX + 8, detailsY + 24, MUTED, false);
            graphics.drawString(font, "Server validates balance", shopX + 8, detailsY + 36, MUTED, false);
        } else {
            graphics.drawString(font, "Select an item", shopX + 8, detailsY, MUTED, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (ShopTarget target : shopTargets) {
                if (target.contains(mouseX, mouseY)) {
                    if (selectedShop == target.index()) {
                        ZoneWarsNetworking.buy(SHOP.get(target.index()).id());
                    } else {
                        selectedShop = target.index();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_I) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private record ShopEntry(String id, String name, Item item, int price) {}

    private record ShopTarget(int index, int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }
}