package dev.imb11.sync;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.TerminalBlock;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.packets.S2CChannelSnapshotPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ChannelManagerPersistence extends SavedData {
    public static final int MAX_CHANNEL_NAME_LENGTH = 48;
    public static final int MAX_WIRE_CHANNEL_NAME_LENGTH = 64;
    public static final int MAX_CHANNELS = 256;
    public static final String DEFAULT_CHANNEL = "Default";
    public static final SavedDataType<ChannelManagerPersistence> TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("glass_channels"), ChannelManagerPersistence::new, CompoundTag.CODEC.xmap(tag -> gather(tag, null), data -> data.save(new CompoundTag(), null)), null);
    private static final SavedDataType<LegacyChannelData> LEGACY_TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("glass_channels"), LegacyChannelData::new, CompoundTag.CODEC.xmap(tag -> LegacyChannelData.gather(tag), data -> data.save(new CompoundTag(), null)), null);

    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Map<String, PendingLink> pendingLinks = new LinkedHashMap<>();
    private final Set<ResourceKey<Level>> importedLegacyDimensions = new LinkedHashSet<>();
    private long registryRevision;
    private transient MinecraftServer server;
    private final Map<String, ProjectionActivity> projectionActivity = new HashMap<>();

    public static ChannelManagerPersistence get(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("Channel state is server-only");
        }
        return get(serverLevel.getServer());
    }

    public static ChannelManagerPersistence get(MinecraftServer server) {
        ChannelManagerPersistence persistence = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        persistence.server = server;
        persistence.ensureDefaultChannel();
        return persistence;
    }

    public static void onLevelLoad(ServerLevel world) {
        MinecraftServer server = world.getServer();
        defer(server, () -> {
            if (server.overworld() != null) {
                get(server).importLegacy(world);
            }
        });
    }

    public static void onBlockEntityLoad(BlockEntity blockEntity, ServerLevel world) {
        if (blockEntity instanceof TerminalBlockEntity terminal) {
            defer(world.getServer(), () -> {
                if (world.hasChunkAt(terminal.getBlockPos()) && world.getBlockEntity(terminal.getBlockPos()) == terminal) {
                    get(world).reconcileTerminal(terminal);
                }
            });
        }
    }

    public long registryRevision() {
        return registryRevision;
    }

    public List<Channel> snapshot() {
        return List.copyOf(channels.values());
    }

    public S2CChannelSnapshotPacket snapshotPacket() {
        return new S2CChannelSnapshotPacket(registryRevision, snapshot());
    }

    public Optional<Channel> channel(String name) {
        String canonical = canonicalChannelName(name);
        return canonical == null ? Optional.empty() : Optional.ofNullable(channels.get(canonical));
    }

    public Optional<ProjectionSource> resolve(String name) {
        return channel(name).map(Channel::source);
    }

    public void recordProjection(String name) {
        if (server != null) {
            resolve(name).ifPresent(source -> projectionActivity.put(source.channel(),
                    new ProjectionActivity(source, server.getTickCount())));
        }
    }

    public boolean isProjecting(ServerLevel level, BlockPos pos, String name) {
        ProjectionActivity activity = projectionActivity.get(name);
        if (server == null || activity == null
                || !sameLocation(activity.source().dimension(), activity.source().pos(), level.dimension(), pos)) {
            return false;
        }
        int age = server.getTickCount() - activity.tick();
        return age >= 0 && age <= 2;
    }

    public boolean containsName(String name) {
        return channel(name).isPresent();
    }

    public boolean createChannel(String name) {
        String canonical = canonicalChannelName(name);
        if (canonical == null || channels.containsKey(canonical) || channels.size() >= MAX_CHANNELS) {
            return false;
        }
        channels.put(canonical, new Channel(canonical, null));
        commit(nextRevision());
        return true;
    }

    public boolean deleteChannel(String name) {
        String canonical = canonicalChannelName(name);
        if (canonical == null || DEFAULT_CHANNEL.equals(canonical)) {
            return false;
        }
        Channel removed = channels.remove(canonical);
        if (removed == null) {
            return false;
        }
        PendingLink pending = pendingLinks.remove(canonical);
        commit(nextRevision());
        if (removed.source() != null) {
            clearLoadedTerminal(removed.source().dimension(), removed.source().pos(), canonical);
        }
        if (pending != null) {
            clearLoadedTerminal(pending.dimension(), pending.pos(), canonical);
        }
        return true;
    }

    public boolean claimTerminal(ServerLevel level, BlockPos pos, Direction facing, String name) {
        String canonical = canonicalChannelName(name);
        if (canonical == null || !channels.containsKey(canonical)) {
            return false;
        }

        BlockPos immutablePos = pos.immutable();
        Channel selected = channels.get(canonical);
        ProjectionSource current = selected.source();
        if (current != null && !sameLocation(current.dimension(), current.pos(), level.dimension(), immutablePos)) {
            return false;
        }
        PendingLink selectedPending = pendingLinks.get(canonical);
        if (selectedPending != null && !sameLocation(selectedPending.dimension(), selectedPending.pos(), level.dimension(), immutablePos)) {
            return false;
        }

        boolean changed = false;

        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            ProjectionSource source = entry.getValue().source();
            if (source != null && sameLocation(source.dimension(), source.pos(), level.dimension(), immutablePos) && !entry.getKey().equals(canonical)) {
                entry.setValue(new Channel(entry.getKey(), null));
                changed = true;
            }
        }

        boolean sourceMatches = current != null
                && sameLocation(current.dimension(), current.pos(), level.dimension(), immutablePos)
                && current.facing() == facing;
        if (!sourceMatches) {
            changed = true;
        }

        if (pendingLinks.entrySet().removeIf(entry -> entry.getKey().equals(canonical)
                || sameLocation(entry.getValue().dimension(), entry.getValue().pos(), level.dimension(), immutablePos))) {
            changed = true;
        }

        if (!changed) {
            return true;
        }

        long revision = nextRevision();
        channels.put(canonical, new Channel(canonical, new ProjectionSource(canonical, level.dimension(), immutablePos, facing, revision)));
        commit(revision);
        return true;
    }

    public boolean unlinkTerminal(ServerLevel level, BlockPos pos) {
        BlockPos immutablePos = pos.immutable();
        boolean changed = false;

        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            ProjectionSource source = entry.getValue().source();
            if (source != null && sameLocation(source.dimension(), source.pos(), level.dimension(), immutablePos)) {
                entry.setValue(new Channel(entry.getKey(), null));
                changed = true;
            }
        }
        if (pendingLinks.entrySet().removeIf(entry -> sameLocation(entry.getValue().dimension(), entry.getValue().pos(), level.dimension(), immutablePos))) {
            changed = true;
        }
        if (changed) {
            commit(nextRevision());
        }
        return changed;
    }

    public void reconcileTerminal(TerminalBlockEntity terminal) {
        if (!(terminal.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = terminal.getBlockPos();
        if (!level.hasChunkAt(pos)) {
            return;
        }
        var state = level.getBlockState(pos);
        if (!state.is(GBlocks.TERMINAL) || !state.hasProperty(TerminalBlock.FACING)) {
            return;
        }

        String desired = sourceChannelAt(level.dimension(), pos);
        if (desired == null) {
            desired = pendingChannelAt(level.dimension(), pos);
        }

        if (desired == null) {
            terminal.setChannelFromServer("");
            return;
        }

        if (claimTerminal(level, pos, state.getValue(TerminalBlock.FACING), desired)) {
            terminal.setChannelFromServer(desired);
        } else {
            terminal.setChannelFromServer("");
        }
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putLong("registry_revision", registryRevision);
        ListTag channelTags = new ListTag();
        for (Channel channel : channels.values()) {
            CompoundTag item = new CompoundTag();
            item.putString("name", channel.name());
            ProjectionSource source = channel.source();
            PendingLink pending = pendingLinks.get(channel.name());
            if (source != null) {
                writeLocation(item, source.dimension(), source.pos());
                item.putString("linked_facing", source.facing().getSerializedName());
                item.putLong("source_revision", source.revision());
            } else if (pending != null) {
                writeLocation(item, pending.dimension(), pending.pos());
            }
            channelTags.add(item);
        }
        tag.put("channels", channelTags);

        ListTag imported = new ListTag();
        for (ResourceKey<Level> dimension : importedLegacyDimensions) {
            imported.add(StringTag.valueOf(dimension.identifier().toString()));
        }
        tag.put("legacy_imported_dimensions", imported);
        return tag;
    }

    public static ChannelManagerPersistence gather(CompoundTag tag, HolderLookup.Provider registryLookup) {
        ChannelManagerPersistence persistence = new ChannelManagerPersistence();
        persistence.registryRevision = Math.max(0L, tag.getLongOr("registry_revision", 0L));
        Map<String, Channel> loadedChannels = new LinkedHashMap<>();
        Map<String, PendingLink> loadedPending = new LinkedHashMap<>();
        ListTag channelTags = tag.getListOrEmpty("channels");
        int count = Math.min(channelTags.size(), MAX_CHANNELS);
        for (int i = 0; i < count; i++) {
            CompoundTag item = channelTags.getCompoundOrEmpty(i);
            String name = canonicalChannelName(item.getStringOr("name", ""));
            if (name == null) {
                continue;
            }
            BlockPos pos = getFromIntArrayNBT("linked_pos", item);
            ResourceKey<Level> dimension = readDimension(item, "linked_dimension");
            Direction facing = Direction.byName(item.getStringOr("linked_facing", "").toLowerCase(Locale.ROOT));
            if (pos != null && dimension != null && facing != null) {
                long sourceRevision = Math.max(0L, item.getLongOr("source_revision", 0L));
                persistence.registryRevision = Math.max(persistence.registryRevision, sourceRevision);
                loadedChannels.put(name, new Channel(name, new ProjectionSource(name, dimension, pos, facing, sourceRevision)));
                loadedPending.remove(name);
            } else {
                loadedChannels.put(name, new Channel(name, null));
                if (pos != null && dimension != null) {
                    loadedPending.put(name, new PendingLink(dimension, pos));
                } else {
                    loadedPending.remove(name);
                }
            }
        }
        persistence.channels.putAll(loadedChannels);
        persistence.pendingLinks.putAll(loadedPending);

        ListTag importedTags = tag.getListOrEmpty("legacy_imported_dimensions");
        for (int i = 0; i < importedTags.size(); i++) {
            ResourceKey<Level> dimension = dimensionKey(Identifier.tryParse(importedTags.getStringOr(i, "")));
            if (dimension != null) {
                persistence.importedLegacyDimensions.add(dimension);
            }
        }
        return persistence;
    }

    @Nullable
    public static BlockPos getFromIntArrayNBT(String key, CompoundTag compound) {
        int[] values = compound.getIntArray(key).orElseGet(() -> new int[0]);
        return values.length < 3 ? null : new BlockPos(values[0], values[1], values[2]);
    }

    @Nullable
    public static String canonicalChannelName(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (normalized.isEmpty()) {
            return null;
        }
        StringBuilder result = new StringBuilder(normalized.length());
        boolean previousWhitespace = false;
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (Character.isISOControl(character)) {
                return null;
            }
            if (Character.isWhitespace(character)) {
                if (!previousWhitespace) {
                    result.append(' ');
                }
                previousWhitespace = true;
            } else {
                result.append(character);
                previousWhitespace = false;
            }
        }
        String canonical = result.toString();
        return canonical.length() > MAX_CHANNEL_NAME_LENGTH ? null : canonical;
    }

    private void ensureDefaultChannel() {
        if (!channels.containsKey(DEFAULT_CHANNEL)) {
            Map<String, Channel> existing = new LinkedHashMap<>(channels);
            channels.clear();
            channels.put(DEFAULT_CHANNEL, new Channel(DEFAULT_CHANNEL, null));
            channels.putAll(existing);
            commit(nextRevision());
        }
    }

    private void importLegacy(ServerLevel world) {
        ResourceKey<Level> dimension = world.dimension();
        if (importedLegacyDimensions.contains(dimension)) {
            return;
        }
        if (dimension.equals(Level.OVERWORLD)) {
            markLegacyImported(dimension);
            return;
        }

        LegacyChannelData legacy = world.getDataStorage().computeIfAbsent(LEGACY_TYPE);
        boolean registryChanged = false;
        for (LegacyEntry entry : legacy.entries()) {
            if (!channels.containsKey(entry.name()) && channels.size() < MAX_CHANNELS) {
                channels.put(entry.name(), new Channel(entry.name(), null));
                registryChanged = true;
            }
            Channel channel = channels.get(entry.name());
            if (entry.pos() != null && channel != null && channel.source() == null && !pendingLinks.containsKey(entry.name())) {
                pendingLinks.put(entry.name(), new PendingLink(dimension, entry.pos()));
                registryChanged = true;
            }
        }

        markLegacyImported(dimension);
        if (registryChanged) {
            commit(nextRevision());
        }
    }

    private void markLegacyImported(ResourceKey<Level> dimension) {
        if (importedLegacyDimensions.add(dimension)) {
            setDirty();
        }
    }

    private void commit(long revision) {
        projectionActivity.entrySet().removeIf(entry -> {
            Channel channel = channels.get(entry.getKey());
            return channel == null || !entry.getValue().source().equals(channel.source());
        });
        registryRevision = revision;
        setDirty();
        if (server != null) {
            GNetworking.broadcastSnapshot(server, this);
        }
    }

    private long nextRevision() {
        return registryRevision == Long.MAX_VALUE ? Long.MAX_VALUE : registryRevision + 1L;
    }

    private String sourceChannelAt(ResourceKey<Level> dimension, BlockPos pos) {
        for (Channel channel : channels.values()) {
            ProjectionSource source = channel.source();
            if (source != null && sameLocation(source.dimension(), source.pos(), dimension, pos)) {
                return channel.name();
            }
        }
        return null;
    }

    private String pendingChannelAt(ResourceKey<Level> dimension, BlockPos pos) {
        for (Map.Entry<String, PendingLink> entry : pendingLinks.entrySet()) {
            PendingLink pending = entry.getValue();
            Channel channel = channels.get(entry.getKey());
            if (channel != null && channel.source() == null && sameLocation(pending.dimension(), pending.pos(), dimension, pos)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void clearLoadedTerminal(ResourceKey<Level> dimension, BlockPos pos, String channel) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunkAt(pos)) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal && channel.equals(terminal.getChannel())) {
            terminal.setChannelFromServer("");
        }
    }

    private static void writeLocation(CompoundTag tag, ResourceKey<Level> dimension, BlockPos pos) {
        tag.putIntArray("linked_pos", new int[]{pos.getX(), pos.getY(), pos.getZ()});
        tag.putString("linked_dimension", dimension.identifier().toString());
    }

    @Nullable
    private static ResourceKey<Level> readDimension(CompoundTag tag, String key) {
        if (!tag.getString(key).isPresent()) {
            return Level.OVERWORLD;
        }
        return dimensionKey(Identifier.tryParse(tag.getStringOr(key, "")));
    }

    @Nullable
    private static ResourceKey<Level> dimensionKey(@Nullable Identifier location) {
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    private static boolean sameLocation(ResourceKey<Level> firstDimension, BlockPos firstPos, ResourceKey<Level> secondDimension, BlockPos secondPos) {
        return firstDimension.equals(secondDimension) && firstPos.equals(secondPos);
    }

    private static void defer(MinecraftServer server, Runnable runnable) {
        server.schedule(new TickTask(server.getTickCount(), runnable));
    }

    private record PendingLink(ResourceKey<Level> dimension, BlockPos pos) {
        private PendingLink {
            pos = pos.immutable();
        }
    }

    private record ProjectionActivity(ProjectionSource source, int tick) {
    }

    private record LegacyEntry(String name, @Nullable BlockPos pos) {
        private LegacyEntry {
            if (pos != null) {
                pos = pos.immutable();
            }
        }
    }

    private static final class LegacyChannelData extends SavedData {
        private final List<LegacyEntry> entries;

        private LegacyChannelData() {
            entries = List.of();
        }

        private LegacyChannelData(List<LegacyEntry> entries) {
            this.entries = List.copyOf(entries);
        }

        private List<LegacyEntry> entries() {
            return entries;
        }

        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registryLookup) {
            return tag;
        }

        private static LegacyChannelData gather(CompoundTag tag) {
            List<LegacyEntry> entries = new ArrayList<>();
            ListTag channels = tag.getListOrEmpty("channels");
            int count = Math.min(channels.size(), MAX_CHANNELS);
            for (int i = 0; i < count; i++) {
                CompoundTag channel = channels.getCompoundOrEmpty(i);
                String name = canonicalChannelName(channel.getStringOr("name", ""));
                BlockPos pos = getFromIntArrayNBT("linked_pos", channel);
                if (name != null) {
                    entries.add(new LegacyEntry(name, pos));
                }
            }
            return new LegacyChannelData(entries);
        }
    }
}
