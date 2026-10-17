package dev.imb11.client.remote;

import dev.imb11.debug.LogThrottle;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import dev.imb11.sync.remote.S2CRemoteUnavailablePacket;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class RemoteSceneHandle {
    final RemoteSubscriptionId id;
    final IdentityHashMap<LevelRenderer, Integer> renderers = new IdentityHashMap<>();
    final LogThrottle grantLog = new LogThrottle(TimeUnit.SECONDS.toNanos(5L));
    private final Map<Long, Control> chunkControls = new LinkedHashMap<>();
    ChunkPos requestedCenter;
    int requestedRadius;
    ChunkPos grantedCenter;
    int grantedRadius;
    RemoteClientScene scene;
    S2CRemoteUnavailablePacket.Reason unavailable;
    long highestSequence = -1L;
    long lastSendTick;
    long stalePackets;
    long discardedApplies;
    int pendingCount;
    long pendingBytes;
    boolean subscribed;
    boolean terminalFailure;

    RemoteSceneHandle(RemoteSubscriptionId id, ChunkPos requestedCenter, int requestedRadius) {
        this.id = id;
        this.requestedCenter = requestedCenter;
        this.requestedRadius = requestedRadius;
    }

    public RemoteSubscriptionId subscription() {
        return id;
    }

    public @Nullable ClientLevel level() {
        return isActive() && scene != null ? scene.level() : null;
    }

    public @Nullable LightTexture lightTexture() {
        return isActive() && scene != null ? scene.lightTexture() : null;
    }

    public int grantedRadius() {
        return isActive() ? grantedRadius : 0;
    }

    public boolean isReady(ChunkPos cameraCenter) {
        return isActive()
                && scene != null
                && unavailable == null
                && withinGrant(cameraCenter, 0)
                && scene.isReady(cameraCenter);
    }

    public boolean isComplete() {
        return isActive()
                && scene != null
                && unavailable == null
                && grantedCenter != null
                && scene.isReady(grantedCenter, grantedRadius + 1);
    }

    public boolean isUnavailable() {
        return !isActive() || unavailable != null;
    }

    public boolean isTerminalFailure() {
        return !isActive() || terminalFailure;
    }

    public Object diagnostics() {
        return RemoteSceneClientManager.diagnostics(this);
    }

    boolean isActive() {
        return RemoteSceneClientManager.isActive(this);
    }

    boolean withinGrant(ChunkPos pos, int padding) {
        return grantedCenter != null
                && Math.abs(pos.x - grantedCenter.x) <= grantedRadius + padding
                && Math.abs(pos.z - grantedCenter.z) <= grantedRadius + padding;
    }

    void recordControl(ChunkPos chunk, long sequence, boolean load) {
        chunkControls.put(chunk.toLong(), new Control(sequence, load));
    }

    boolean controlCurrent(ChunkPos chunk, long sequence, boolean load) {
        Control control = chunkControls.get(chunk.toLong());
        return control != null && control.sequence == sequence && control.load == load;
    }

    boolean updateCurrent(ChunkPos chunk, long sequence) {
        Control control = chunkControls.get(chunk.toLong());
        return control == null || sequence >= control.sequence;
    }

    private record Control(long sequence, boolean load) {
    }
}
