package dev.imb11.client.remote;

import com.mojang.logging.LogUtils;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.debug.RemoteSceneDiagnostics;
import dev.imb11.client.renderer.projection.ProjectionSections;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import dev.imb11.sync.remote.RemoteWorldState;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import dev.imb11.sync.remote.S2CRemoteSubscriptionPacket;
import dev.imb11.sync.remote.S2CRemoteEntitiesPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import dev.imb11.client.renderer.projection.ProjectionLightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import dev.imb11.projection.ProjectionChunkRegion;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class RemoteClientScene implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STORAGE_VIEW_DISTANCE = 2;
    private final boolean mainWorld;
    private final ProjectionSource source;
    private final ResourceKey<DimensionType> dimensionType;
    private final boolean hardcore;
    private final boolean debug;
    private final boolean flat;
    private final long seedHash;
    private final ClientLevel level;
    private final ClientChunkCache chunkCache;
    private final LevelLightEngine lightEngine;
    private final ProjectionLightmap lightTexture;
    private final RemoteSceneEntities entities;
    private final Map<Long, LinkedHashSet<RemoteSubscriptionId>> chunkOwners = new LinkedHashMap<>();
    private final Map<RemoteSubscriptionId, Set<LevelRenderer>> renderers = new LinkedHashMap<>();
    private final LinkedHashSet<Long> deferredLightSections = new LinkedHashSet<>();
    private final Map<LevelRenderer, Set<Long>> pendingDirtySections = new IdentityHashMap<>();
    private RemoteWorldState state;
    private int skyFlashTime;
    private boolean applying;
    private boolean closed;

    private RemoteClientScene(
            ProjectionSource source,
            Minecraft minecraft,
            ResourceKey<DimensionType> dimensionType,
            boolean hardcore,
            boolean debug,
            boolean flat,
            long seedHash,
            ClientLevel level,
            ProjectionLightmap lightTexture,
            RemoteWorldState state
    ) {
        this.source = source;
        this.dimensionType = dimensionType;
        this.hardcore = hardcore;
        this.debug = debug;
        this.flat = flat;
        this.seedHash = seedHash;
        this.level = level;
        this.mainWorld = level == minecraft.level;
        this.chunkCache = level.getChunkSource();
        this.lightEngine = chunkCache.getLightEngine();
        this.lightTexture = lightTexture;
        this.entities = new RemoteSceneEntities(level);
        this.state = state;
    }

    static RemoteClientScene create(Minecraft minecraft, S2CRemoteSubscriptionPacket packet) {
        ClientPacketListener connection = Objects.requireNonNull(minecraft.getConnection());
        Holder<DimensionType> dimensionType = connection.registryAccess()
                .lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(packet.dimensionType());
        if (minecraft.level != null && minecraft.level.dimension().equals(packet.subscription().source().dimension())) {
            return new RemoteClientScene(packet.subscription().source(), minecraft, packet.dimensionType(),
                    packet.hardcore(), packet.debug(), packet.flat(), packet.seedHash(),
                    minecraft.level, new ProjectionLightmap(), packet.state());
        }
        RemoteSceneSinkExtractor extractor = new RemoteSceneSinkExtractor(minecraft);
        ProjectionLightmap lightTexture = null;
        try {
            ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(packet.difficulty(), packet.hardcore(), packet.flat());
            ClientLevel level = new RemoteSceneLevel(
                    connection,
                    levelData,
                    packet.subscription().source().dimension(),
                    dimensionType,
                    STORAGE_VIEW_DISTANCE,
                    extractor,
                    packet.debug(),
                    packet.seedHash(),
                    packet.seaLevel()
            );
            ChunkPos sourceChunk = ChunkPos.containing(packet.subscription().source().pos());
            level.getChunkSource().updateViewCenter(sourceChunk.x(), sourceChunk.z());
            level.getChunkSource().updateViewRadius(STORAGE_VIEW_DISTANCE);
            lightTexture = new ProjectionLightmap();
            RemoteClientScene scene = new RemoteClientScene(
                    packet.subscription().source(),
                    minecraft,
                    packet.dimensionType(),
                    packet.hardcore(),
                    packet.debug(),
                    packet.flat(),
                    packet.seedHash(),
                    level,
                    lightTexture,
                    packet.state()
            );
            extractor.bind(scene);
            scene.applyWorldState(packet.state());
            ProjectionRenderContext.registerRemoteLightUpdates(scene.chunkCache, scene::onLightUpdate);
            return scene;
        } catch (RuntimeException exception) {
            if (lightTexture != null) {
                ProjectionLightmap failedProjectionLightmap = lightTexture;
                cleanupCreation("light texture", () -> closeProjectionLightmap(failedProjectionLightmap));
            }
            throw exception;
        }
    }

    ProjectionSource source() {
        return source;
    }

    ClientLevel level() {
        return level;
    }

    ProjectionLightmap lightTexture() {
        return lightTexture;
    }

    boolean matches(S2CRemoteSubscriptionPacket packet) {
        return source.dimension().equals(packet.subscription().source().dimension())
                && dimensionType.equals(packet.dimensionType())
                && hardcore == packet.hardcore()
                && debug == packet.debug()
                && flat == packet.flat()
                && seedHash == packet.seedHash()
                && level.getSeaLevel() == packet.seaLevel();
    }

    void attachRenderer(RemoteSubscriptionId subscription, LevelRenderer renderer) {
        if (closed) {
            return;
        }
        boolean alreadyAttached = renderers.values().stream().anyMatch(attached -> attached.contains(renderer));
        renderers.computeIfAbsent(subscription, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(renderer);
        if (!alreadyAttached && rendererActive(renderer)) {
            for (Map.Entry<Long, LinkedHashSet<RemoteSubscriptionId>> entry : chunkOwners.entrySet()) {
                if (entry.getValue().contains(subscription)) {
                    notifyRendererChunk(renderer, ChunkPos.unpack(entry.getKey()));
                }
            }
        }
    }

    void detachRenderer(RemoteSubscriptionId subscription, LevelRenderer renderer) {
        Set<LevelRenderer> attached = renderers.get(subscription);
        if (attached != null && attached.remove(renderer) && attached.isEmpty()) {
            renderers.remove(subscription);
        }
    }

    void setRegion(RemoteSubscriptionId subscription, ChunkPos center, int radius) {
        Set<ChunkPos> next = new ProjectionChunkRegion(center, radius).withNeighbors().chunks();
        for (ChunkPos pos : next) {
            LinkedHashSet<RemoteSubscriptionId> owners = chunkOwners.computeIfAbsent(pos.pack(), ignored -> new LinkedHashSet<>());
            if (owners.isEmpty()) {
                ProjectionChunkStorage.of(level).retain(pos);
            }
            owners.add(subscription);
        }
        for (long packed : List.copyOf(chunkOwners.keySet())) {
            ChunkPos pos = ChunkPos.unpack(packed);
            if (!next.contains(pos)) {
                unloadChunk(subscription, pos);
            }
        }
    }

    void applyChunk(RemoteSubscriptionId subscription, ClientboundLevelChunkWithLightPacket packet) {
        ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
        if (closed || !ownedBy(subscription, pos)) {
            return;
        }
        ProjectionChunkStorage storage = ProjectionChunkStorage.of(level);
        if (storage.vanilla(pos)) {
            return;
        }
        beginApply();
        boolean committed = false;
        try {
            var data = packet.getChunkData();
            FriendlyByteBuf buffer = data.getReadBuffer();
            LevelChunk chunk;
            try {
                chunk = storage.replace(level, pos.x(), pos.z(), buffer, data.getHeightmaps(),
                        data.getBlockEntitiesTagsConsumer(pos.x(), pos.z()), false);
            } finally {
                buffer.release();
            }
            applyLightLayers(pos, packet.getLightData());
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                lightEngine.updateSectionStatus(SectionPos.of(pos, level.getSectionYFromSectionIndex(index)), sections[index].hasOnlyAir());
            }
            chunk.setLightCorrect(true);
            committed = true;
        } finally {
            finishApply(committed);
        }
        if (committed) {
            notifyOwnersChunk(pos);
        }
    }

    void unloadChunk(RemoteSubscriptionId subscription, ChunkPos pos) {
        LinkedHashSet<RemoteSubscriptionId> owners = chunkOwners.get(pos.pack());
        if (owners == null || !owners.contains(subscription)) {
            return;
        }
        if (owners.size() == 1) {
            if (!ProjectionChunkStorage.of(level).vanilla(pos)) {
                resetOwnersChunk(pos);
            }
            chunkOwners.remove(pos.pack());
            ProjectionChunkStorage.of(level).release(level, pos);
        } else {
            owners.remove(subscription);
        }
    }

    void applyBlockUpdates(
            RemoteSubscriptionId subscription,
            int sectionX,
            int sectionY,
            int sectionZ,
            ClientboundSectionBlocksUpdatePacket updates
    ) {
        ChunkPos pos = new ChunkPos(sectionX, sectionZ);
        if (closed || !ownedBy(subscription, pos) || ProjectionChunkStorage.of(level).vanilla(pos)
                || sectionY < level.getMinSectionY() || sectionY > level.getMaxSectionY()) {
            return;
        }
        updates.runUpdates((block, state) -> {
            if (SectionPos.blockToSectionCoord(block.getX()) == sectionX
                    && SectionPos.blockToSectionCoord(block.getY()) == sectionY
                    && SectionPos.blockToSectionCoord(block.getZ()) == sectionZ) {
                level.setServerVerifiedBlockState(block, state, 19);
            }
        });
    }

    void applyLight(RemoteSubscriptionId subscription, ChunkPos pos, ClientboundLightUpdatePacketData light) {
        if (!ownedBy(subscription, pos) || ProjectionChunkStorage.of(level).vanilla(pos)) {
            return;
        }
        beginApply();
        boolean committed = false;
        try {
            applyLightLayers(pos, light);
            committed = true;
        } finally {
            finishApply(committed);
        }
    }

    void applyWorldState(RemoteWorldState state) {
        if (closed) {
            return;
        }
        this.state = state;
        if (mainWorld) {
            return;
        }
        level.setTimeFromServer(state.time().gameTime());
        level.clockManager().handleUpdates(state.time().gameTime(), state.time().clockUpdates());
        level.environmentAttributes().invalidateTickCache();
        level.setRainLevel(state.rainLevel());
        level.setThunderLevel(state.thunderLevel());
        skyFlashTime = state.skyFlashTime();
        level.setSkyFlashTime(skyFlashTime);
        level.updateSkyBrightness();
    }

    void advanceClock() {
        if (closed || mainWorld || state == null) {
            return;
        }
        level.setTimeFromServer(level.getGameTime() + 1L);
        level.clockManager().tick(level.getGameTime());
        level.environmentAttributes().invalidateTickCache();
        if (skyFlashTime > 0) {
            level.setSkyFlashTime(--skyFlashTime);
        }
        level.updateSkyBrightness();
        entities.tick();
    }

    void onVanillaEntityAdded(net.minecraft.world.entity.Entity entity) {
        entities.onVanillaAdded(entity);
    }

    boolean onVanillaEntityRemoved(int id) {
        return entities.onVanillaRemoved(id);
    }

    void applyEntities(RemoteSubscriptionId subscription, List<S2CRemoteEntitiesPacket.EntityMessage> messages) {
        if (!closed) {
            entities.apply(subscription, messages);
        }
    }

    void applyBlockEntities(RemoteSubscriptionId subscription, List<ClientboundBlockEntityDataPacket> updates) {
        for (ClientboundBlockEntityDataPacket update : updates) {
            BlockPos pos = update.getPos();
            if (closed || !ownedBy(subscription, ChunkPos.containing(pos)) || ProjectionChunkStorage.of(level).vanilla(ChunkPos.containing(pos))) {
                continue;
            }
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null && blockEntity.getType() == update.getType()) {
                blockEntity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), update.getTag()));
                dirtyOwnersSection(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                        SectionPos.blockToSectionCoord(pos.getZ()), false);
            }
        }
    }

    boolean isReady(ChunkPos cameraCenter) {
        return isReady(cameraCenter, 1);
    }

    boolean isReady(ChunkPos center, int radius) {
        if (closed) {
            return false;
        }
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                ChunkPos pos = new ChunkPos(center.x() + offsetX, center.z() + offsetZ);
                if (!chunkPresent(pos) || !chunkLit(pos)) {
                    return false;
                }
            }
        }
        return true;
    }

    RemoteSceneDiagnostics.ChunkStats chunkDiagnostics(ChunkPos center, int radius) {
        long sharedSubscriptions = chunkOwners.values().stream().flatMap(Set::stream).distinct().count();
        return RemoteSceneDiagnostics.ChunkStats.scan(mainWorld, sharedSubscriptions, closed, center, radius,
                this::chunkPresent, this::chunkLit);
    }

    private boolean chunkPresent(ChunkPos pos) {
        return chunkCache.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null && chunkOwners.containsKey(pos.pack());
    }

    private boolean chunkLit(ChunkPos pos) {
        return lightEngine.lightOnInColumn(SectionPos.of(pos, 0).asLong());
    }

    void releaseSubscription(RemoteSubscriptionId subscription) {
        entities.release(subscription);
        List<ChunkPos> owned = new ArrayList<>();
        for (Map.Entry<Long, LinkedHashSet<RemoteSubscriptionId>> entry : chunkOwners.entrySet()) {
            if (entry.getValue().contains(subscription)) {
                owned.add(ChunkPos.unpack(entry.getKey()));
            }
        }
        for (ChunkPos pos : owned) {
            unloadChunk(subscription, pos);
        }
    }

    void dirtyBlock(BlockPos pos) {
        if (!applying && !closed) {
            dirtyBlocks(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        }
    }

    void dirtyBlocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (applying || closed) {
            return;
        }
        int minSectionX = SectionPos.blockToSectionCoord(minX - 1);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - 1);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + 1);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + 1);
        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    dirtyOwnersSection(sectionX, sectionY, sectionZ, false);
                }
            }
        }
    }

    void dirtySectionWithNeighbors(int sectionX, int sectionY, int sectionZ) {
        if (!applying && !closed) {
            dirtyOwnersSection(sectionX, sectionY, sectionZ, true);
        }
    }

    void dirtySection(int sectionX, int sectionY, int sectionZ) {
        if (!applying && !closed) {
            dirtyOwnersSection(sectionX, sectionY, sectionZ, false);
        }
    }

    private void applyLightLayers(ChunkPos pos, ClientboundLightUpdatePacketData light) {
        int sectionCount = lightEngine.getLightSectionCount();
        BitSet skyMask = light.getSkyYMask();
        BitSet emptySkyMask = light.getEmptySkyYMask();
        BitSet blockMask = light.getBlockYMask();
        BitSet emptyBlockMask = light.getEmptyBlockYMask();
        if (skyMask.length() > sectionCount
                || emptySkyMask.length() > sectionCount
                || blockMask.length() > sectionCount
                || emptyBlockMask.length() > sectionCount) {
            throw new IllegalArgumentException("remote light section count mismatch");
        }
        applyLightLayer(pos, LightLayer.SKY, skyMask, emptySkyMask, light.getSkyUpdates());
        applyLightLayer(pos, LightLayer.BLOCK, blockMask, emptyBlockMask, light.getBlockUpdates());
        lightEngine.setLightEnabled(pos, true);
    }

    private void applyLightLayer(ChunkPos pos, LightLayer layer, BitSet dataMask, BitSet emptyMask, List<byte[]> updates) {
        Iterator<byte[]> updateIterator = updates.iterator();
        for (int index = 0; index < lightEngine.getLightSectionCount(); index++) {
            boolean hasData = dataMask.get(index);
            if (!hasData && !emptyMask.get(index)) {
                continue;
            }
            DataLayer data = hasData ? new DataLayer(updateIterator.next()) : new DataLayer();
            int sectionY = lightEngine.getMinLightSection() + index;
            lightEngine.queueSectionData(layer, SectionPos.of(pos, sectionY), data);
        }
        if (updateIterator.hasNext()) {
            throw new IllegalArgumentException("remote light update count mismatch");
        }
    }

    private void beginApply() {
        applying = true;
        deferredLightSections.clear();
    }

    private void finishApply(boolean committed) {
        applying = false;
        if (!committed) {
            deferredLightSections.clear();
            return;
        }
        for (long section : deferredLightSections) {
            SectionPos pos = SectionPos.of(section);
            dirtyOwnersSection(pos.x(), pos.y(), pos.z(), true);
        }
        deferredLightSections.clear();
    }

    private void onLightUpdate(LightLayer layer, SectionPos sectionPos) {
        if (closed) {
            return;
        }
        if (applying) {
            deferredLightSections.add(sectionPos.asLong());
        } else {
            dirtyOwnersSection(sectionPos.x(), sectionPos.y(), sectionPos.z(), true);
        }
    }

    private boolean ownedBy(RemoteSubscriptionId subscription, ChunkPos pos) {
        LinkedHashSet<RemoteSubscriptionId> owners = chunkOwners.get(pos.pack());
        return owners != null && owners.contains(subscription);
    }

    private void notifyOwnersChunk(ChunkPos pos) {
        for (LevelRenderer renderer : ownerRenderers(pos)) {
            notifyRendererChunk(renderer, pos);
        }
    }

    private void notifyRendererChunk(LevelRenderer renderer, ChunkPos pos) {
        queueDirtyChunk(renderer, pos, true);
    }

    private void queueDirtyChunk(LevelRenderer renderer, ChunkPos pos, boolean neighbors) {
        int padding = neighbors ? 1 : 0;
        for (int x = pos.x() - padding; x <= pos.x() + padding; x++) {
            for (int z = pos.z() - padding; z <= pos.z() + padding; z++) {
                for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                    queueDirtySection(renderer, x, y, z, false);
                }
            }
        }
    }

    private void dirtyOwnersSection(int sectionX, int sectionY, int sectionZ, boolean neighbors) {
        ChunkPos chunk = new ChunkPos(sectionX, sectionZ);
        for (LevelRenderer renderer : ownerRenderers(chunk)) {
            queueDirtySection(renderer, sectionX, sectionY, sectionZ, neighbors);
        }
    }

    private void queueDirtySection(LevelRenderer renderer, int sectionX, int sectionY, int sectionZ, boolean neighbors) {
        int padding = neighbors ? 1 : 0;
        for (int x = sectionX - padding; x <= sectionX + padding; x++) {
            for (int y = sectionY - padding; y <= sectionY + padding; y++) {
                for (int z = sectionZ - padding; z <= sectionZ + padding; z++) {
                    if (ProjectionSections.find(renderer, x, y, z) != null) {
                        pendingDirtySections.computeIfAbsent(renderer, ignored -> new LinkedHashSet<>()).add(SectionPos.asLong(x, y, z));
                    }
                }
            }
        }
    }

    void flushUpdates() {
        if (closed) {
            return;
        }
        if (!mainWorld && lightEngine.hasLightWork()) {
            lightEngine.runLightUpdates();
        }
        for (Map.Entry<LevelRenderer, Set<Long>> entry : pendingDirtySections.entrySet()) {
            for (long packed : entry.getValue()) {
                SectionPos pos = SectionPos.of(packed);
                ProjectionSections.markDirty(entry.getKey(), pos.x(), pos.y(), pos.z());
            }
        }
        pendingDirtySections.clear();
    }

    private Set<LevelRenderer> ownerRenderers(ChunkPos pos) {
        LinkedHashSet<RemoteSubscriptionId> owners = chunkOwners.get(pos.pack());
        if (owners == null || owners.isEmpty()) {
            return Set.of();
        }
        Set<LevelRenderer> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RemoteSubscriptionId owner : owners) {
            for (LevelRenderer renderer : renderers.getOrDefault(owner, Set.of())) {
                if (rendererActive(renderer)) {
                    result.add(renderer);
                }
            }
        }
        return result;
    }

    private static boolean rendererActive(LevelRenderer renderer) {
        return renderer.viewArea() != null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pendingDirtySections.clear();
        cleanup("entities", entities::clear);
        cleanup("chunk and light storage", () -> {
            for (long packed : List.copyOf(chunkOwners.keySet())) {
                ProjectionChunkStorage.of(level).release(level, ChunkPos.unpack(packed));
            }
        });
        if (!mainWorld) {
            ProjectionRenderContext.unregisterRemoteLightUpdates(chunkCache);
            try {
                level.close();
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Failed to close remote client level {}", source.key(), exception);
            }
        }
        chunkOwners.clear();
        renderers.clear();
        deferredLightSections.clear();
        if (mainWorld) {
            return;
        }
        cleanup("tint caches", level::clearTintCaches);
        cleanup("light texture", () -> closeProjectionLightmap(lightTexture));
    }

    private void resetOwnersChunk(ChunkPos pos) {
        for (LevelRenderer renderer : ownerRenderers(pos)) {
            ProjectionSections.resetColumn(renderer, level, pos);
        }
    }

    private static void closeProjectionLightmap(ProjectionLightmap lightTexture) {
        lightTexture.close();
    }

    private void cleanup(String resource, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to close remote scene {} {}", source.key(), resource, exception);
        }
    }

    private static void cleanupCreation(String resource, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to close incomplete remote scene {}", resource, exception);
        }
    }
}
