package dev.imb11.client;

import dev.imb11.compat.SableCompatibility;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.SourcePose;
import dev.imb11.sync.packets.S2CSourcePosePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class ClientSourcePoses {
    private static final Map<ProjectionSource.Key, Entry> POSES = new HashMap<>();

    private ClientSourcePoses() {
    }

    public static void accept(S2CSourcePosePacket packet) {
        if (packet.pose() == null) {
            POSES.remove(packet.source());
            return;
        }
        Entry entry = POSES.get(packet.source());
        if (entry == null) {
            POSES.put(packet.source(), new Entry(packet.pose(), packet.pose()));
        } else {
            entry.current = packet.pose();
        }
    }

    public static void tick() {
        for (Entry entry : POSES.values()) {
            entry.previous = entry.current;
        }
    }

    public static boolean isOnSubLevel(ProjectionSource source) {
        ClientLevel level = Minecraft.getInstance().level;
        return POSES.containsKey(source.key()) || level != null && SableCompatibility.isOnSubLevel(level, source.pos());
    }

    @Nullable
    public static SourcePose resolve(ProjectionSource source, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.dimension().equals(source.dimension())) {
            SourcePose live = SableCompatibility.sourcePose(level, source.pos());
            if (live != null) {
                return live;
            }
        }
        Entry entry = POSES.get(source.key());
        return entry == null ? null : entry.previous.lerp(entry.current, partialTick);
    }

    public static String describe(ProjectionSource source) {
        if (!isOnSubLevel(source)) {
            return source.pos().toShortString();
        }
        SourcePose pose = resolve(source, 1.0F);
        return pose == null ? "an unloaded sub-level" : BlockPos.containing(pose.position()).toShortString() + " on a sub-level";
    }

    public static void clear() {
        POSES.clear();
    }

    private static final class Entry {
        private SourcePose previous;
        private SourcePose current;

        private Entry(SourcePose previous, SourcePose current) {
            this.previous = previous;
            this.current = current;
        }
    }
}
