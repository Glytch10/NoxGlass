package dev.imb11.client.gui;

import dev.imb11.client.ClientSourcePoses;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.platform.ClientNetworking;
import dev.imb11.sync.Channel;
import dev.imb11.sync.packets.C2SProjectorChannelChangedPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class ProjectorBlockScreen extends ChannelScreen<ProjectorBlockGUI> {
    public ProjectorBlockScreen(ProjectorBlockGUI menu, Inventory inventory, Component title) {
        super(menu, inventory, title, false);
    }

    @Override
    protected boolean isCurrent(Channel channel) {
        return minecraft.level.getBlockEntity(menu.pos) instanceof ProjectorBlockEntity projector
                && channel.name().equals(projector.getChannel());
    }

    @Override
    protected boolean canChoose(Channel channel) {
        return channel.source() != null;
    }

    @Override
    protected void choose(Channel channel) {
        ClientNetworking.send(new C2SProjectorChannelChangedPacket(menu.pos, channel.name()));
    }

    @Override
    protected Component chooseLabel() {
        return Component.literal("Project this channel");
    }

    @Override
    protected Component status(Channel channel) {
        if (isCurrent(channel)) {
            return Component.literal("Showing on this projector");
        }
        if (channel.source() == null) {
            return Component.literal("Not linked to a terminal");
        }
        return Component.literal("Terminal at " + ClientSourcePoses.describe(channel.source())
                + " (" + channel.source().dimension().location() + ")");
    }

    @Override
    protected String unavailableLabel() {
        return "no terminal";
    }
}
