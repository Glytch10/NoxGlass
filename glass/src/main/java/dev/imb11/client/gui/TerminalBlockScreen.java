package dev.imb11.client.gui;

import dev.imb11.platform.ClientNetworking;
import dev.imb11.sync.Channel;
import dev.imb11.sync.packets.C2SRemoveLinkedChannelPacket;
import dev.imb11.sync.packets.C2STerminalChannelChangedPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class TerminalBlockScreen extends ChannelScreen<TerminalBlockGUI> {
    public TerminalBlockScreen(TerminalBlockGUI menu, Inventory inventory, Component title) {
        super(menu, inventory, title, true);
    }

    @Override
    protected boolean isCurrent(Channel channel) {
        return channel.source() != null && minecraft.level != null
                && channel.source().dimension().equals(minecraft.level.dimension())
                && channel.source().pos().equals(menu.pos);
    }

    @Override
    protected boolean canChoose(Channel channel) {
        return channel.source() == null;
    }

    @Override
    protected void choose(Channel channel) {
        ClientNetworking.send(new C2STerminalChannelChangedPacket(menu.pos, channel.name()));
    }

    @Override
    protected void unlink() {
        ClientNetworking.send(new C2SRemoveLinkedChannelPacket(menu.pos));
    }

    @Override
    protected Component chooseLabel() {
        return Component.literal("Link to this channel");
    }

    @Override
    protected Component status(Channel channel) {
        if (isCurrent(channel)) {
            return Component.literal("Linked to this terminal");
        }
        if (channel.source() == null) {
            return Component.literal("Available");
        }
        return Component.literal("Used by terminal at " + channel.source().pos().toShortString()
                + " (" + channel.source().dimension().location() + ")");
    }

    @Override
    protected String unavailableLabel() {
        return "in use";
    }
}
