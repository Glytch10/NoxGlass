package dev.imb11.client.remote;

import com.mojang.logging.LogUtils;
import dev.imb11.sync.remote.C2SRemoteSubscribePacket;
import dev.imb11.debug.RemoteSceneDiagnostics;
import dev.imb11.sync.remote.C2SRemoteUnsubscribePacket;
import dev.imb11.sync.remote.C2SRemoteUpdatePacket;
import dev.imb11.sync.remote.C2SRemoteChunkAckPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import dev.imb11.sync.remote.RemoteSceneServerManager;
import dev.imb11.sync.remote.RemoteWorldState;
import dev.imb11.sync.remote.S2CRemoteBlockUpdatesPacket;
import dev.imb11.sync.remote.S2CRemoteChunkPacket;
import dev.imb11.sync.remote.S2CRemoteEntitiesPacket;
import dev.imb11.sync.remote.S2CRemoteBlockEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import dev.imb11.sync.remote.S2CRemoteLightPacket;
import dev.imb11.sync.remote.S2CRemoteSubscriptionPacket;
import dev.imb11.sync.remote.S2CRemoteUnavailablePacket;
import dev.imb11.sync.remote.S2CRemoteUnloadPacket;
import dev.imb11.sync.remote.S2CRemoteWorldStatePacket;
import dev.imb11.platform.ClientNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RemoteSceneClientManager {
    public static final int MAX_PENDING_PER_SUBSCRIPTION = 64;
    public static final int MAX_PENDING_GLOBAL = 256;
    public static final int MAX_APPLIES_PER_TICK = 8;
    public static final long MAX_PENDING_BYTES_PER_SUBSCRIPTION = 8L * 1024L * 1024L;
    public static final long MAX_PENDING_BYTES_GLOBAL = 32L * 1024L * 1024L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int HEARTBEAT_TICKS = 40;
    private static final Map<RemoteSubscriptionId, RemoteSceneHandle> SUBSCRIPTIONS = new LinkedHashMap<>();
    private static final Map<ResourceKey<Level>, RemoteClientScene> SCENES = new LinkedHashMap<>();
    private static final ArrayDeque<PendingApply> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<C2SRemoteChunkAckPacket> ACKNOWLEDGEMENTS = new ArrayDeque<>();
    private static final long APPLY_BUDGET_NANOS = 2_000_000L;
    private static long clientTicks;
    private static long pendingBytes;
    private static int pendingCount;
    private static ClientLevel mainLevel;
    private static boolean receiversRegistered;

    private RemoteSceneClientManager() {
    }

    public static void registerReceivers() {
        if (receiversRegistered) {
            return;
        }
        receiversRegistered = true;
        ClientNetworking.registerReceiver(S2CRemoteSubscriptionPacket.PACKET_ID, RemoteSceneClientManager::accept);
        ClientNetworking.registerReceiver(S2CRemoteUnavailablePacket.PACKET_ID, RemoteSceneClientManager::accept);
        ClientNetworking.registerReceiver(S2CRemoteChunkPacket.PACKET_ID, RemoteSceneClientManager::accept);
        ClientNetworking.registerReceiver(S2CRemoteEntitiesPacket.PACKET_ID, RemoteSceneClientManager::accept);
        ClientNetworking.registerReceiver(S2CRemoteBlockEntitiesPacket.PACKET_ID, RemoteSceneClientManager::accept);
        ClientNetworking.registerReceiver(S2CRemoteUnloadPacket.PACKET_ID, RemoteSceneClientManager::accept);
        ClientNetworking.registerReceiver(S2CRemoteBlockUpdatesPacket.PACKET_ID, RemoteSceneClientManager::accept);
        ClientNetworking.registerReceiver(S2CRemoteLightPacket.PACKET_ID, RemoteSceneClientManager::accept);
        ClientNetworking.registerReceiver(S2CRemoteWorldStatePacket.PACKET_ID, RemoteSceneClientManager::accept);
    }

    public static RemoteSceneHandle acquire(RemoteSubscriptionId subscription, ChunkPos cameraCenter, int requestedRadius) {
        Objects.requireNonNull(subscription);
        Objects.requireNonNull(cameraCenter);
        int radius = clampRadius(requestedRadius);
        RemoteSceneHandle state = SUBSCRIPTIONS.get(subscription);
        if (state != null) {
            updateState(state, cameraCenter, radius);
            return state;
        }
        state = new RemoteSceneHandle(subscription, cameraCenter, radius);
        SUBSCRIPTIONS.put(subscription, state);
        state.subscribed = send(new C2SRemoteSubscribePacket(subscription, cameraCenter, radius));
        state.lastSendTick = clientTicks;
        LOGGER.info("[GLASS projector] client subscribe id={} center={} radius={} sent={}", subscription, cameraCenter, radius, state.subscribed);
        return state;
    }

    public static void update(RemoteSceneHandle handle, ChunkPos cameraCenter, int requestedRadius) {
        Objects.requireNonNull(cameraCenter);
        if (isActive(handle)) {
            updateState(handle, cameraCenter, clampRadius(requestedRadius));
        }
    }

    public static void release(RemoteSceneHandle handle) {
        if (handle == null || !SUBSCRIPTIONS.remove(handle.id, handle)) {
            return;
        }
        LOGGER.info("[GLASS projector] client release id={} pending={} pendingBytes={} lastSequence={} unavailable={}",
                handle.id, handle.pendingCount, handle.pendingBytes, handle.highestSequence, handle.unavailable);
        if (handle.subscribed) {
            send(new C2SRemoteUnsubscribePacket(handle.id));
        }
        removePending(handle);
        detachState(handle);
    }

    public static void attachRenderer(RemoteSceneHandle handle, LevelRenderer renderer) {
        Objects.requireNonNull(renderer);
        if (isActive(handle) && handle.renderers.merge(renderer, 1, Integer::sum) == 1 && handle.scene != null) {
            handle.scene.attachRenderer(handle.id, renderer);
        }
    }

    public static void detachRenderer(RemoteSceneHandle handle, LevelRenderer renderer) {
        Objects.requireNonNull(renderer);
        Integer count = isActive(handle) ? handle.renderers.get(renderer) : null;
        if (count == null) {
            return;
        }
        if (count > 1) {
            handle.renderers.put(renderer, count - 1);
            return;
        }
        handle.renderers.remove(renderer);
        if (handle.scene != null) {
            handle.scene.detachRenderer(handle.id, renderer);
        }
    }

    public static void tick(Minecraft minecraft) {
        clientTicks++;
        if (!minecraft.isPaused()) {
            for (RemoteClientScene scene : List.copyOf(SCENES.values())) {
                try {
                    scene.advanceClock();
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to tick remote scene {}", scene.source().key(), exception);
                    failScene(scene, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
                }
            }
        }
        int applied = 0;
        long applyStarted = System.nanoTime();
        while (applied < MAX_APPLIES_PER_TICK && System.nanoTime() - applyStarted < APPLY_BUDGET_NANOS) {
            PendingApply pending = PENDING.pollFirst();
            if (pending == null) {
                break;
            }
            accountDequeued(pending.state, pending.command);
            RemoteSceneHandle current = SUBSCRIPTIONS.get(pending.state.id);
            if (current == pending.state && current.scene != null && !current.terminalFailure) {
                try {
                    pending.command.apply(current);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to apply remote scene payload {}", current.id, exception);
                    failScene(current.scene, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
                }
            } else {
                pending.state.discardedApplies++;
            }
            if (bounded(pending.command)) {
                applied++;
            }
        }
        for (RemoteClientScene scene : List.copyOf(SCENES.values())) {
            try {
                scene.flushUpdates();
            } catch (RuntimeException exception) {
                LOGGER.warn("[GLASS projector] failed to finish remote scene updates {}", scene.source().key(), exception);
                failScene(scene, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED);
            }
        }
        while (!ACKNOWLEDGEMENTS.isEmpty()) {
            send(ACKNOWLEDGEMENTS.removeFirst());
        }
        for (RemoteSceneHandle state : List.copyOf(SUBSCRIPTIONS.values())) {
            if (state.terminalFailure || clientTicks - state.lastSendTick < HEARTBEAT_TICKS) {
                continue;
            }
            if (!state.subscribed) {
                state.subscribed = send(new C2SRemoteSubscribePacket(state.id, state.requestedCenter, state.requestedRadius));
            } else {
                send(new C2SRemoteUpdatePacket(state.id, state.requestedCenter, state.requestedRadius));
            }
            state.lastSendTick = clientTicks;
        }
    }

    public static void onVanillaEntityAdded(ClientLevel level, Entity entity) {
        RemoteClientScene scene = SCENES.get(level.dimension());
        if (scene != null && scene.level() == level) {
            scene.onVanillaEntityAdded(entity);
        }
    }

    public static boolean onVanillaEntityRemoved(ClientLevel level, int id) {
        RemoteClientScene scene = SCENES.get(level.dimension());
        return scene != null && scene.level() == level && scene.onVanillaEntityRemoved(id);
    }

    public static void onMainLevelChanged(@Nullable ClientLevel level) {
        if (mainLevel == level) {
            return;
        }
        if (mainLevel != null || level == null) {
            clear(true);
        }
        mainLevel = level;
    }

    public static void onResourceReload() {
        clear(true);
    }

    public static void clearConnection() {
        clear(false);
        mainLevel = null;
    }

    static boolean isActive(RemoteSceneHandle handle) {
        return handle != null && SUBSCRIPTIONS.get(handle.id) == handle;
    }

    static Object diagnostics(RemoteSceneHandle state) {
        if (!isActive(state)) {
            return "epoch=" + state.id.epoch() + " state=released";
        }
        return new RemoteSceneDiagnostics(state.id.epoch(), state.subscribed, state.unavailable, state.terminalFailure,
                state.requestedCenter, state.requestedRadius, state.grantedCenter, state.grantedRadius,
                state.withinGrant(state.requestedCenter, 0), state.pendingCount, state.pendingBytes, PENDING.size(), pendingBytes,
                state.highestSequence, state.stalePackets, state.discardedApplies, clientTicks - state.lastSendTick,
                state.scene == null || state.grantedCenter == null ? null
                        : state.scene.chunkDiagnostics(state.grantedCenter, state.grantedRadius + 1));
    }

    private static void accept(S2CRemoteSubscriptionPacket packet) {
        RemoteSceneHandle state = SUBSCRIPTIONS.get(packet.subscription());
        if (state == null || state.terminalFailure) {
            return;
        }
        RemoteClientScene scene = SCENES.get(state.id.source().dimension());
        if (scene != null && !scene.matches(packet)) {
            fail(state, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED, true);
            return;
        }
        if (scene == null) {
            try {
                scene = RemoteClientScene.create(Minecraft.getInstance(), packet);
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to create remote scene {}", state.id.source().key(), exception);
                fail(state, S2CRemoteUnavailablePacket.Reason.UNSUPPORTED, true);
                return;
            }
            SCENES.put(state.id.source().dimension(), scene);
        }
        if (state.scene != scene) {
            if (state.scene != null) {
                detachStateScene(state);
            }
            state.scene = scene;
            for (LevelRenderer renderer : state.renderers.keySet()) {
                scene.attachRenderer(state.id, renderer);
            }
        }
        state.grantedCenter = packet.grantedCenter();
        state.grantedRadius = packet.grantedRadius();
        state.unavailable = null;
        state.subscribed = true;
        scene.setRegion(state.id, state.grantedCenter, state.grantedRadius);
        scene.applyWorldState(packet.state());
        if (state.grantLog.ready(System.nanoTime())) {
            LOGGER.info("[GLASS projector] client grant id={} center={} radius={} requestedCenter={} requestedRadius={}",
                    state.id, state.grantedCenter, state.grantedRadius, state.requestedCenter, state.requestedRadius);
        }
    }

    private static void accept(S2CRemoteUnavailablePacket packet) {
        RemoteSceneHandle state = SUBSCRIPTIONS.get(packet.subscription());
        if (state == null) {
            return;
        }
        boolean terminal = packet.reason() != S2CRemoteUnavailablePacket.Reason.BUDGET;
        fail(state, packet.reason(), terminal);
    }

    private static void accept(S2CRemoteChunkPacket packet) {
        enqueue(packet.subscription(), new ChunkUpdate(packet.sequence(),
                new ChunkPos(packet.data().getX(), packet.data().getZ()), packet.data(), packet.encodedBytes()));
    }

    private static void accept(S2CRemoteUnloadPacket packet) {
        enqueue(packet.subscription(), new UnloadUpdate(packet.sequence(), new ChunkPos(packet.chunkX(), packet.chunkZ())));
    }

    private static void accept(S2CRemoteEntitiesPacket packet) {
        enqueue(packet.subscription(), new EntityUpdates(packet.sequence(), packet.messages()));
    }

    private static void accept(S2CRemoteBlockEntitiesPacket packet) {
        enqueue(packet.subscription(), new BlockEntityUpdates(packet.sequence(), packet.updates()));
    }

    private static void accept(S2CRemoteBlockUpdatesPacket packet) {
        enqueue(packet.subscription(), new BlockUpdate(
                packet.sequence(),
                packet.sectionX(),
                packet.sectionY(),
                packet.sectionZ(),
                packet.updates()
        ));
    }

    private static void accept(S2CRemoteLightPacket packet) {
        enqueue(packet.subscription(), new LightUpdate(
                packet.sequence(),
                new ChunkPos(packet.data().getX(), packet.data().getZ()),
                packet.data().getLightData()
        ));
    }

    private static void accept(S2CRemoteWorldStatePacket packet) {
        enqueue(packet.subscription(), new WorldStateUpdate(packet.sequence(), packet.state()));
    }

    private static void enqueue(RemoteSubscriptionId id, SceneUpdate command) {
        RemoteSceneHandle state = SUBSCRIPTIONS.get(id);
        if (state == null || state.terminalFailure) {
            return;
        }
        if (command.sequence() <= state.highestSequence) {
            state.stalePackets++;
            return;
        }
        state.highestSequence = command.sequence();
        boolean bounded = bounded(command);
        if (bounded && (state.pendingCount >= MAX_PENDING_PER_SUBSCRIPTION
                || pendingCount >= MAX_PENDING_GLOBAL
                || state.pendingBytes + command.bytes() > MAX_PENDING_BYTES_PER_SUBSCRIPTION
                || pendingBytes + command.bytes() > MAX_PENDING_BYTES_GLOBAL)) {
            fail(state, S2CRemoteUnavailablePacket.Reason.BUDGET, true);
            return;
        }
        if (command instanceof ChunkControl control) {
            state.recordControl(control.chunk(), command.sequence(), control.loads());
        }
        PENDING.addLast(new PendingApply(state, command));
        if (bounded) {
            state.pendingCount++;
            pendingCount++;
        }
        state.pendingBytes += command.bytes();
        pendingBytes += command.bytes();
    }

    private static int clampRadius(int requestedRadius) {
        return Math.clamp(requestedRadius, RemoteSceneServerManager.MIN_RADIUS, RemoteSceneServerManager.MAX_RADIUS);
    }

    private static void updateState(RemoteSceneHandle state, ChunkPos cameraCenter, int radius) {
        if (state.requestedCenter.equals(cameraCenter) && state.requestedRadius == radius) {
            return;
        }
        state.requestedCenter = cameraCenter;
        state.requestedRadius = radius;
        if (state.subscribed && !state.terminalFailure) {
            send(new C2SRemoteUpdatePacket(state.id, state.requestedCenter, radius));
            state.lastSendTick = clientTicks;
        }
    }

    private static void fail(RemoteSceneHandle state, S2CRemoteUnavailablePacket.Reason reason, boolean terminal) {
        LOGGER.warn("[GLASS projector] client unavailable id={} reason={} terminal={} pending={} pendingBytes={} globalPending={} globalPendingBytes={} lastSequence={} grantedCenter={} grantedRadius={}",
                state.id, reason, terminal, state.pendingCount, state.pendingBytes, PENDING.size(), pendingBytes,
                state.highestSequence, state.grantedCenter, state.grantedRadius);
        if (terminal && state.subscribed) {
            send(new C2SRemoteUnsubscribePacket(state.id));
        }
        removePending(state);
        state.grantedCenter = null;
        state.grantedRadius = 0;
        state.unavailable = reason;
        state.terminalFailure = terminal;
        if (terminal) {
            state.subscribed = false;
        }
        if (state.scene != null) {
            try {
                state.scene.releaseSubscription(state.id);
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to release remote scene subscription {}", state.id, exception);
            }
        }
    }

    private static void failScene(RemoteClientScene scene, S2CRemoteUnavailablePacket.Reason reason) {
        List<RemoteSceneHandle> affected = SUBSCRIPTIONS.values().stream()
                .filter(state -> state.scene == scene)
                .toList();
        for (RemoteSceneHandle state : affected) {
            fail(state, reason, true);
        }
    }

    private static void detachState(RemoteSceneHandle state) {
        if (state.scene != null) {
            detachStateScene(state);
        }
        state.renderers.clear();
    }

    private static void detachStateScene(RemoteSceneHandle state) {
        RemoteClientScene scene = state.scene;
        state.scene = null;
        try {
            scene.releaseSubscription(state.id);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to release detached remote scene subscription {}", state.id, exception);
        }
        for (LevelRenderer renderer : state.renderers.keySet()) {
            scene.detachRenderer(state.id, renderer);
        }
        if (SUBSCRIPTIONS.values().stream().noneMatch(candidate -> candidate.scene == scene)) {
            SCENES.remove(scene.source().dimension(), scene);
            try {
                scene.close();
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to close remote scene {}", scene.source().key(), exception);
            }
        }
    }

    private static void removePending(RemoteSceneHandle state) {
        Iterator<PendingApply> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingApply pending = iterator.next();
            if (pending.state != state) {
                continue;
            }
            iterator.remove();
            accountDequeued(state, pending.command);
        }
    }

    private static void accountDequeued(RemoteSceneHandle state, SceneUpdate command) {
        if (bounded(command)) {
            state.pendingCount = Math.max(0, state.pendingCount - 1);
            pendingCount = Math.max(0, pendingCount - 1);
        }
        state.pendingBytes = Math.max(0L, state.pendingBytes - command.bytes());
        pendingBytes = Math.max(0L, pendingBytes - command.bytes());
    }

    private static boolean bounded(SceneUpdate command) {
        return !(command instanceof UnloadUpdate);
    }

    private static void clear(boolean notifyServer) {
        if (notifyServer) {
            for (RemoteSceneHandle state : SUBSCRIPTIONS.values()) {
                if (state.subscribed) {
                    send(new C2SRemoteUnsubscribePacket(state.id));
                }
            }
        }
        PENDING.clear();
        pendingCount = 0;
        ACKNOWLEDGEMENTS.clear();
        pendingBytes = 0L;
        for (RemoteClientScene scene : new ArrayList<>(SCENES.values())) {
            try {
                scene.close();
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to close remote scene {}", scene.source().key(), exception);
            }
        }
        SCENES.clear();
        SUBSCRIPTIONS.clear();
    }

    private static boolean send(CustomPacketPayload packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return false;
        }
        try {
            if (!ClientNetworking.canSend(packet.type())) {
                return false;
            }
            ClientNetworking.send(packet);
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static long lightBytes(ClientboundLightUpdatePacketData data) {
        return 128L + (long) (data.getSkyYMask().cardinality() + data.getBlockYMask().cardinality()) * 2048;
    }

    private record PendingApply(RemoteSceneHandle state, SceneUpdate command) {
    }

    private sealed interface SceneUpdate permits ChunkControl, BlockUpdate, LightUpdate, WorldStateUpdate, EntityUpdates, BlockEntityUpdates {
        long sequence();

        long bytes();

        void apply(RemoteSceneHandle state);
    }

    private sealed interface ChunkControl extends SceneUpdate permits ChunkUpdate, UnloadUpdate {
        ChunkPos chunk();

        boolean loads();
    }

    private record ChunkUpdate(long sequence, ChunkPos chunk, ClientboundLevelChunkWithLightPacket data, int encodedBytes) implements ChunkControl {
        @Override
        public long bytes() {
            return 64L + encodedBytes;
        }

        @Override
        public boolean loads() {
            return true;
        }

        @Override
        public void apply(RemoteSceneHandle state) {
            if (state.controlCurrent(chunk, sequence, true) && state.withinGrant(chunk, 1)) {
                state.scene.applyChunk(state.id, data);
                ACKNOWLEDGEMENTS.add(new C2SRemoteChunkAckPacket(state.id, sequence, chunk));
            }
        }
    }

    private record UnloadUpdate(long sequence, ChunkPos chunk) implements ChunkControl {
        @Override
        public long bytes() {
            return 32L;
        }

        @Override
        public boolean loads() {
            return false;
        }

        @Override
        public void apply(RemoteSceneHandle state) {
            if (state.controlCurrent(chunk, sequence, false) && !state.withinGrant(chunk, 1)) {
                state.scene.unloadChunk(state.id, chunk);
            }
        }
    }

    private record BlockUpdate(
            long sequence,
            int sectionX,
            int sectionY,
            int sectionZ,
            ClientboundSectionBlocksUpdatePacket updates
    ) implements SceneUpdate {
        @Override
        public long bytes() {
            return 64L + S2CRemoteBlockUpdatesPacket.MAX_UPDATES * 16L;
        }

        @Override
        public void apply(RemoteSceneHandle state) {
            ChunkPos chunk = new ChunkPos(sectionX, sectionZ);
            if (state.updateCurrent(chunk, sequence)) {
                state.scene.applyBlockUpdates(state.id, sectionX, sectionY, sectionZ, updates);
            }
        }
    }

    private record LightUpdate(long sequence, ChunkPos chunk, ClientboundLightUpdatePacketData light) implements SceneUpdate {
        @Override
        public long bytes() {
            return lightBytes(light);
        }

        @Override
        public void apply(RemoteSceneHandle state) {
            if (state.updateCurrent(chunk, sequence)) {
                state.scene.applyLight(state.id, chunk, light);
            }
        }
    }

    private record WorldStateUpdate(long sequence, RemoteWorldState worldState) implements SceneUpdate {
        @Override
        public long bytes() {
            return 64L;
        }

        @Override
        public void apply(RemoteSceneHandle state) {
            state.scene.applyWorldState(worldState);
        }
    }

    private record EntityUpdates(long sequence, List<S2CRemoteEntitiesPacket.EntityMessage> messages) implements SceneUpdate {
        @Override
        public long bytes() {
            return 64L + messages.size() * 1024L;
        }

        @Override
        public void apply(RemoteSceneHandle state) {
            state.scene.applyEntities(state.id, messages);
        }
    }

    private record BlockEntityUpdates(long sequence, List<ClientboundBlockEntityDataPacket> updates) implements SceneUpdate {
        @Override
        public long bytes() {
            return 64L + updates.size() * 4096L;
        }

        @Override
        public void apply(RemoteSceneHandle state) {
            state.scene.applyBlockEntities(state.id, updates.stream()
                    .filter(update -> state.updateCurrent(new ChunkPos(update.getPos()), sequence))
                    .toList());
        }
    }
}
