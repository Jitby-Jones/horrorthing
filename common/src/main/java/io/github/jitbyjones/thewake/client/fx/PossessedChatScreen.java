package io.github.jitbyjones.thewake.client.fx;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** The chat box, while it is the one typing: your keys do nothing, and escape does not close it. */
public class PossessedChatScreen extends ChatScreen {
    public PossessedChatScreen(String initial) {
        super(initial);
    }

    @Override
    protected void init() {
        super.init();
        // Plain white letters: no red where a command would not parse, nothing suggested.
        input.setFormatter((text, cursor) -> FormattedCharSequence.forward(text, Style.EMPTY));
    }

    /** The box and the history, without the command hints under them. */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        minecraft.gui.getChat().render(graphics, minecraft.gui.getGuiTicks(), mouseX, mouseY, true);
        graphics.fill(2, height - 14, width - 2, height - 2, minecraft.options.getBackgroundColor(Integer.MIN_VALUE));
        input.render(graphics, mouseX, mouseY, partialTick);
    }

    void type(String text) {
        if (input != null) {
            input.setValue(text);
            input.moveCursorToEnd(false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
