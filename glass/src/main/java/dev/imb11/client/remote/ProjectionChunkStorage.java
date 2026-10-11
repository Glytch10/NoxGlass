package dev.imb11.client.remote;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ProjectionChunkStorage {
    private final ClientChunkCache cache;
    private final Map<Long, Entry> chunks = new ConcurrentHashMap<>();

    public ProjectionChunkStorage(ClientChunkCache cache) {
        this.cache = cache;
    }

    public static ProjectionChunkStorage of(ClientLevel level) {
        return ((ProjectionChunkAccess) level.getChunkSource()).glass$projectionChunks();
    }

    public LevelChunk get(int x, int z) {
        Entry entry = chunks.get(ChunkPos.pack(x, z));
        return entry == null ? null : entry.chunk;
    }

    public boolean contains(int x, int z) {
        return chunks.containsKey(ChunkPos.pack(x, z));
    }

    public boolean retained(ChunkPos pos) {
        Entry entry = chunks.get(pos.pack());
        return entry != null && entry.references > 0;
    }

    public boolean vanilla(ChunkPos pos) {
        Entry entry = chunks.get(pos.pack());
        return entry != null && entry.vanilla;
    }

    public void retain(ChunkPos pos) {
        Entry entry = chunks.get(pos.pack());
        if (entry == null) {
            LevelChunk existing = cache.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
            entry = new Entry(existing, existing != null);
            chunks.put(pos.pack(), entry);
        }
        entry.references++;
    }

    public void release(ClientLevel level, ChunkPos pos) {
        Entry entry = chunks.get(pos.pack());
        if (entry == null || entry.references < 1 || --entry.references > 0 || entry.vanilla) {
            return;
        }
        chunks.remove(pos.pack());
        if (entry.chunk != null) {
            level.unload(entry.chunk);
            clearLight(level, pos);
            markUnloaded(entry.chunk);
        }
    }

    public LevelChunk replace(ClientLevel level, int x, int z, FriendlyByteBuf data, java.util.Map<net.minecraft.world.level.levelgen.Heightmap.Types, long[]> heightmaps,
                              Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities, boolean vanilla) {
        Entry entry = chunks.get(ChunkPos.pack(x, z));
        if (entry == null) {
            throw new IllegalStateException("Chunk has no projection owner");
        }
        if (!vanilla && entry.vanilla && entry.chunk != null) {
            return entry.chunk;
        }
        if (entry.chunk == null) {
            entry.chunk = new LevelChunk(level, new ChunkPos(x, z));
        }
        entry.vanilla |= vanilla;
        entry.chunk.replaceWithPacketData(data, heightmaps, blockEntities);
        markLoaded(entry.chunk);
        level.onChunkLoaded(new ChunkPos(x, z));
        return entry.chunk;
    }

    public boolean dropVanilla(ClientLevel level, ChunkPos pos) {
        Entry entry = chunks.get(pos.pack());
        if (entry == null) {
            return false;
        }
        entry.vanilla = false;
        if (entry.references == 0) {
            chunks.remove(pos.pack());
            if (entry.chunk != null) {
                level.unload(entry.chunk);
                markUnloaded(entry.chunk);
            }
        }
        return true;
    }

    public boolean protectFromUnload(LevelChunk chunk) {
        Entry entry = chunks.get(chunk.getPos().pack());
        if (entry == null || entry.chunk != chunk) {
            return false;
        }
        if (entry.references > 0) {
            entry.vanilla = false;
            markLoaded(chunk);
            return true;
        }
        chunks.remove(chunk.getPos().pack());
        return false;
    }

    private void markLoaded(LevelChunk chunk) {
        long chunkNode = chunk.getPos().pack();
        cache.removedLoadedChunks().remove(chunkNode);
        cache.addedLoadedChunks().add(chunkNode);
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            long sectionNode = SectionPos.asLong(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(index), chunk.getPos().z());
            if (sections[index].hasOnlyAir()) {
                cache.removedEmptySections().remove(sectionNode);
                cache.addedEmptySections().add(sectionNode);
            } else {
                cache.addedEmptySections().remove(sectionNode);
                cache.removedEmptySections().add(sectionNode);
            }
        }
    }

    private void markUnloaded(LevelChunk chunk) {
        long chunkNode = chunk.getPos().pack();
        cache.addedLoadedChunks().remove(chunkNode);
        cache.removedLoadedChunks().add(chunkNode);
        for (int index = 0; index < chunk.getSections().length; index++) {
            long sectionNode = SectionPos.asLong(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(index), chunk.getPos().z());
            cache.addedEmptySections().remove(sectionNode);
            cache.removedEmptySections().add(sectionNode);
        }
    }

    private static void clearLight(ClientLevel level, ChunkPos pos) {
        var light = level.getLightEngine();
        light.setLightEnabled(pos, false);
        for (int y = light.getMinLightSection(); y < light.getMaxLightSection(); y++) {
            SectionPos section = SectionPos.of(pos, y);
            light.queueSectionData(LightLayer.SKY, section, null);
            light.queueSectionData(LightLayer.BLOCK, section, null);
        }
        for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
            light.updateSectionStatus(SectionPos.of(pos, y), true);
        }
    }

    private static final class Entry {
        private volatile LevelChunk chunk;
        private int references;
        private boolean vanilla;

        private Entry(LevelChunk chunk, boolean vanilla) {
            this.chunk = chunk;
            this.vanilla = vanilla;
        }
    }
}
