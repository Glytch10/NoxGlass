package dev.imb11.sync.packets;

import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.SourcePose;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record S2CSourcePosePacket(ProjectionSource.Key source, @Nullable SourcePose pose) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CSourcePosePacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "source_pose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSourcePosePacket> PACKET_CODEC = StreamCodec.ofMember(S2CSourcePosePacket::write, S2CSourcePosePacket::read);

    public S2CSourcePosePacket {
        source = Objects.requireNonNull(source);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(source.channel(), ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH);
        buf.writeResourceLocation(source.dimension().location());
        buf.writeBlockPos(source.pos());
        buf.writeBoolean(pose != null);
        if (pose != null) {
            pose.write(buf);
        }
    }

    private static S2CSourcePosePacket read(RegistryFriendlyByteBuf buf) {
        ProjectionSource.Key source = new ProjectionSource.Key(
                buf.readUtf(ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH),
                ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation()),
                buf.readBlockPos()
        );
        return new S2CSourcePosePacket(source, buf.readBoolean() ? SourcePose.read(buf) : null);
    }
}
