package io.github.jitbyjones.thewake.client.fx;

import io.github.jitbyjones.thewake.net.PossessPayload;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;

/**
 * Your chat box, typing by itself. It opens, and the letters come one at a time, unevenly, the way
 * a person types: something for you to read; a pause; then backspace, back to nothing; then the
 * command. Enter. The box closes. It is in your history if you press up.
 */
public final class PossessionClient {
    private static final RandomSource RANDOM = RandomSource.create();

    private enum Step { OPEN, SAY, HOLD, ERASE, COMMAND, SEND, DONE }

    @Nullable
    private static PossessPayload current;
    private static Step step = Step.DONE;
    private static int index;
    private static int wait;

    private PossessionClient() {}

    public static boolean active() {
        return current != null && step != Step.DONE;
    }

    public static void begin(PossessPayload payload) {
        current = payload;
        step = Step.OPEN;
        index = 0;
        wait = payload.hurried() ? 2 : 10;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (current == null || step == Step.DONE || mc.player == null) {
            return;
        }
        if (step != Step.OPEN && !(mc.screen instanceof PossessedChatScreen)) {
            // You closed it. It opens it again, where it was.
            mc.setScreen(new PossessedChatScreen(text()));
        }
        if (--wait > 0) {
            return;
        }
        PossessPayload p = current;
        switch (step) {
            case OPEN -> {
                mc.setScreen(new PossessedChatScreen(""));
                step = p.say().isEmpty() ? Step.COMMAND : Step.SAY;
                index = 0;
                wait = p.hurried() ? 1 : 14;
            }
            case SAY -> {
                index++;
                key();
                set(p.say().substring(0, index));
                if (index >= p.say().length()) {
                    step = Step.HOLD;
                    wait = 30;
                } else {
                    wait = 2 + RANDOM.nextInt(5) + (p.say().charAt(index - 1) == ' ' ? 3 : 0);
                }
            }
            case HOLD -> {
                step = Step.ERASE;
                wait = 1;
            }
            case ERASE -> {
                index--;
                key();
                set(p.say().substring(0, Math.max(0, index)));
                if (index <= 0) {
                    step = Step.COMMAND;
                    index = 0;
                    wait = 16;
                } else {
                    wait = 1 + RANDOM.nextInt(2);
                }
            }
            case COMMAND -> {
                index++;
                key();
                set(p.command().substring(0, index));
                if (index >= p.command().length()) {
                    step = Step.SEND;
                    wait = p.hurried() ? 4 : 18;
                } else {
                    wait = p.hurried() ? 1 : 2 + RANDOM.nextInt(3);
                }
            }
            case SEND -> {
                String command = p.command();
                if (mc.player.connection != null) {
                    mc.gui.getChat().addRecentChat(command);
                    // Without the right to run it, the server does it itself (no red error to give it away).
                    if (command.startsWith("/") && mc.player.hasPermissions(2)) {
                        mc.player.connection.sendCommand(command.substring(1));
                    }
                }
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.6F));
                mc.setScreen(null);
                step = Step.DONE;
                current = null;
            }
            default -> {
            }
        }
    }

    private static String text() {
        if (current == null) {
            return "";
        }
        return switch (step) {
            case SAY, HOLD, ERASE -> current.say().substring(0, Math.max(0, Math.min(index, current.say().length())));
            case COMMAND, SEND -> current.command().substring(0, Math.max(0, Math.min(index, current.command().length())));
            default -> "";
        };
    }

    private static void set(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PossessedChatScreen chat) {
            chat.type(text);
        }
    }

    private static void key() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6F + RANDOM.nextFloat() * 0.3F, 0.08F));
    }

    public static void reset() {
        current = null;
        step = Step.DONE;
    }
}
