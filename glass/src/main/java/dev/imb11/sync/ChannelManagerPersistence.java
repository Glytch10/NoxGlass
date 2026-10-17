package dev.imb11.sync;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.TerminalBlock;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.compat.SableCompatibility;
import dev.imb11.sync.packets.S2CChannelSnapshotPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
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
    public static final SavedData.Factory<ChannelManagerPersistence> TYPE = new SavedData.Factory<>(ChannelManagerPersistence::new, ChannelManagerPersistence::gather, null);
    private static final SavedData.Factory<LegacyChannelData> LEGACY_TYPE = new SavedData.Factory<>(LegacyChannelData::new, LegacyChannelData::gather, null);
    private static final String DATA_ID = "glass_channels";
    private static final int TERMINAL_MOVE_TICKS = 20;

    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Map<String, PendingLink> pendingLinks = new LinkedHashMap<>();
    private final Set<ResourceKey<Level>> importedLegacyDimensions = new LinkedHashSet<>();
    private long registryRevision;
    private transient MinecraftServer server;
    private final Map<String, ProjectionActivity> projectionActivity = new HashMap<>();
    private final Map<String, TrackedPose> sourcePoses = new HashMap<>();
    private final Map<String, TrackedPose> broadcastPoses = new HashMap<>();
    private final Map<String, MovingTerminal> movingTerminals = new HashMap<>();

    public static ChannelManagerPersistence get(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("Channel state is server-only");
        }
        return get(serverLevel.getServer());
    }

    public static ChannelManagerPersistence get(MinecraftServer server) {
        ChannelManagerPersistence persistence = server.overworld().getDataStorage().computeIfAbsent(TYPE, DATA_ID);
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

    @Nullable
    public SourcePose sourcePose(ProjectionSource source) {
        TrackedPose tracked = sourcePoses.get(source.channel());
        return tracked != null && tracked.source().equals(source.key()) ? tracked.pose() : null;
    }

    public List<TrackedPose> sourcePoses() {
        return List.copyOf(sourcePoses.values());
    }

    public void tickSourcePoses() {
        if (server == null) {
            return;
        }
        for (Channel channel : channels.values()) {
            ProjectionSource source = channel.source();
            if (source == null) {
                continue;
            }
            ServerLevel level = server.getLevel(source.dimension());
            if (level == null || !SableCompatibility.isOnSubLevel(level, source.pos()) || !level.hasChunkAt(source.pos())) {
                continue;
            }
            SourcePose pose = SableCompatibility.sourcePose(level, source.pos());
            if (pose == null) {
                continue;
            }
            TrackedPose previous = sourcePoses.get(channel.name());
            if (previous == null || !previous.source().equals(source.key()) || !previous.pose().closeTo(pose)) {
                sourcePoses.put(channel.name(), new TrackedPose(source.key(), pose));
                setDirty();
            }
        }
        expireTerminalMoves();
        broadcastSourcePoses();
    }

    public void beginTerminalMove(ServerLevel level, BlockPos pos) {
        String channel = sourceChannelAt(level.dimension(), pos);
        boolean pending = channel == null;
        if (pending) {
            channel = pendingChannelAt(level.dimension(), pos);
        }
        if (channel == null || server == null) {
            unlinkTerminal(level, pos);
            return;
        }
        movingTerminals.put(channel, new MovingTerminal(level.dimension(), pos.immutable(), pending, server.getTickCount()));
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
        if (desired == null && completeTerminalMove(level, pos, terminal.getChannel())) {
            desired = terminal.getChannel();
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

    @Override
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
                TrackedPose tracked = sourcePoses.get(channel.name());
                if (tracked != null && tracked.source().equals(source.key())) {
                    item.put("linked_pose", tracked.pose().save());
                }
            } else if (pending != null) {
                writeLocation(item, pending.dimension(), pending.pos());
            }
            channelTags.add(item);
        }
        tag.put("channels", channelTags);

        ListTag imported = new ListTag();
        for (ResourceKey<Level> dimension : importedLegacyDimensions) {
            imported.add(StringTag.valueOf(dimension.location().toString()));
        }
        tag.put("legacy_imported_dimensions", imported);
        return tag;
    }

    public static ChannelManagerPersistence gather(CompoundTag tag, HolderLookup.Provider registryLookup) {
        ChannelManagerPersistence persistence = new ChannelManagerPersistence();
        persistence.registryRevision = Math.max(0L, tag.getLong("registry_revision"));
        Map<String, Channel> loadedChannels = new LinkedHashMap<>();
        Map<String, PendingLink> loadedPending = new LinkedHashMap<>();
        ListTag channelTags = tag.getList("channels", Tag.TAG_COMPOUND);
        int count = Math.min(channelTags.size(), MAX_CHANNELS);
        for (int i = 0; i < count; i++) {
            CompoundTag item = channelTags.getCompound(i);
            String name = canonicalChannelName(item.getString("name"));
            if (name == null) {
                continue;
            }
            BlockPos pos = getFromIntArrayNBT("linked_pos", item);
            ResourceKey<Level> dimension = readDimension(item, "linked_dimension");
            Direction facing = Direction.byName(item.getString("linked_facing").toLowerCase(Locale.ROOT));
            if (pos != null && dimension != null && facing != null) {
                long sourceRevision = Math.max(0L, item.getLong("source_revision"));
                persistence.registryRevision = Math.max(persistence.registryRevision, sourceRevision);
                ProjectionSource source = new ProjectionSource(name, dimension, pos, facing, sourceRevision);
                loadedChannels.put(name, new Channel(name, source));
                SourcePose pose = item.contains("linked_pose", Tag.TAG_COMPOUND) ? SourcePose.load(item.getCompound("linked_pose")) : null;
                if (pose != null) {
                    persistence.sourcePoses.put(name, new TrackedPose(source.key(), pose));
                }
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

        ListTag importedTags = tag.getList("legacy_imported_dimensions", Tag.TAG_STRING);
        for (int i = 0; i < importedTags.size(); i++) {
            ResourceKey<Level> dimension = dimensionKey(ResourceLocation.tryParse(importedTags.getString(i)));
            if (dimension != null) {
                persistence.importedLegacyDimensions.add(dimension);
            }
        }
        return persistence;
    }

    @Nullable
    public static BlockPos getFromIntArrayNBT(String key, CompoundTag compound) {
        int[] values = compound.getIntArray(key);
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

        LegacyChannelData legacy = world.getDataStorage().computeIfAbsent(LEGACY_TYPE, DATA_ID);
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
        sourcePoses.entrySet().removeIf(entry -> {
            Channel channel = channels.get(entry.getKey());
            return channel == null || channel.source() == null || !entry.getValue().source().equals(channel.source().key());
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

    private boolean completeTerminalMove(ServerLevel level, BlockPos pos, String carried) {
        MovingTerminal moving = carried.isEmpty() ? null : movingTerminals.get(carried);
        Channel channel = moving == null ? null : channels.get(carried);
        if (channel == null || sameLocation(moving.dimension(), moving.pos(), level.dimension(), pos)) {
            return false;
        }
        PendingLink pending = pendingLinks.get(carried);
        boolean stillOwned = moving.pending()
                ? channel.source() == null && pending != null && sameLocation(pending.dimension(), pending.pos(), moving.dimension(), moving.pos())
                : channel.source() != null && sameLocation(channel.source().dimension(), channel.source().pos(), moving.dimension(), moving.pos());
        movingTerminals.remove(carried);
        if (!stillOwned) {
            return false;
        }
        BlockPos immutablePos = pos.immutable();
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            ProjectionSource source = entry.getValue().source();
            if (source != null && !entry.getKey().equals(carried) && sameLocation(source.dimension(), source.pos(), level.dimension(), immutablePos)) {
                entry.setValue(new Channel(entry.getKey(), null));
            }
        }
        pendingLinks.entrySet().removeIf(entry -> !entry.getKey().equals(carried)
                && sameLocation(entry.getValue().dimension(), entry.getValue().pos(), level.dimension(), immutablePos));
        long revision = nextRevision();
        if (moving.pending()) {
            pendingLinks.put(carried, new PendingLink(level.dimension(), immutablePos));
        } else {
            channels.put(carried, new Channel(carried, new ProjectionSource(carried, level.dimension(), immutablePos,
                    channel.source().facing(), revision)));
        }
        commit(revision);
        return true;
    }

    private void expireTerminalMoves() {
        if (server == null || movingTerminals.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        for (Map.Entry<String, MovingTerminal> entry : List.copyOf(movingTerminals.entrySet())) {
            MovingTerminal moving = entry.getValue();
            if (now - moving.tick() < TERMINAL_MOVE_TICKS) {
                continue;
            }
            movingTerminals.remove(entry.getKey());
            ServerLevel level = server.getLevel(moving.dimension());
            if (level != null && !(level.hasChunkAt(moving.pos()) && level.getBlockState(moving.pos()).is(GBlocks.TERMINAL))) {
                unlinkTerminal(level, moving.pos());
            }
        }
    }

    private void broadcastSourcePoses() {
        for (Map.Entry<String, TrackedPose> entry : sourcePoses.entrySet()) {
            TrackedPose sent = broadcastPoses.get(entry.getKey());
            TrackedPose current = entry.getValue();
            if (sent == null || !sent.source().equals(current.source()) || !sent.pose().closeTo(current.pose())) {
                broadcastPoses.put(entry.getKey(), current);
                GNetworking.broadcastSourcePose(server, current.source(), current.pose());
            }
        }
        for (Map.Entry<String, TrackedPose> entry : List.copyOf(broadcastPoses.entrySet())) {
            TrackedPose current = sourcePoses.get(entry.getKey());
            if (current == null || !current.source().equals(entry.getValue().source())) {
                broadcastPoses.remove(entry.getKey());
                GNetworking.broadcastSourcePose(server, entry.getValue().source(), null);
            }
        }
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
        tag.putString("linked_dimension", dimension.location().toString());
    }

    @Nullable
    private static ResourceKey<Level> readDimension(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            return Level.OVERWORLD;
        }
        return dimensionKey(ResourceLocation.tryParse(tag.getString(key)));
    }

    @Nullable
    private static ResourceKey<Level> dimensionKey(@Nullable ResourceLocation location) {
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    private static boolean sameLocation(ResourceKey<Level> firstDimension, BlockPos firstPos, ResourceKey<Level> secondDimension, BlockPos secondPos) {
        return firstDimension.equals(secondDimension) && firstPos.equals(secondPos);
    }

    private static void defer(MinecraftServer server, Runnable runnable) {
        server.tell(new TickTask(server.getTickCount(), runnable));
    }

    private record PendingLink(ResourceKey<Level> dimension, BlockPos pos) {
        private PendingLink {
            pos = pos.immutable();
        }
    }

    private record ProjectionActivity(ProjectionSource source, int tick) {
    }

    public record TrackedPose(ProjectionSource.Key source, SourcePose pose) {
    }

    private record MovingTerminal(ResourceKey<Level> dimension, BlockPos pos, boolean pending, int tick) {
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

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registryLookup) {
            return tag;
        }

        private static LegacyChannelData gather(CompoundTag tag, HolderLookup.Provider registryLookup) {
            List<LegacyEntry> entries = new ArrayList<>();
            ListTag channels = tag.getList("channels", Tag.TAG_COMPOUND);
            int count = Math.min(channels.size(), MAX_CHANNELS);
            for (int i = 0; i < count; i++) {
                CompoundTag channel = channels.getCompound(i);
                String name = canonicalChannelName(channel.getString("name"));
                BlockPos pos = getFromIntArrayNBT("linked_pos", channel);
                if (name != null) {
                    entries.add(new LegacyEntry(name, pos));
                }
            }
            return new LegacyChannelData(entries);
        }
    }
}
