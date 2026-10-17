package dev.imb11.blocks.entity;

import dev.imb11.blocks.GBlocks;
import dev.imb11.debug.ProjectorPowerTrace;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.sounds.GSounds;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.platform.PlatformBlockEntity;
import dev.imb11.platform.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class ProjectorBlockEntity extends PlatformBlockEntity implements ExtendedMenuProvider<BlockPos> {
    public static BlockEntityType<ProjectorBlockEntity> BLOCK_ENTITY_TYPE = BlockEntityType.Builder.of(ProjectorBlockEntity::new, GBlocks.PROJECTOR).build(null);
    public boolean active = false;
    private String channel = "";

    private ProjectionSurface projectionSurface;
    private int revealDistance = -1;
    private int clientRevealDistance = -1;
    private boolean clientProjectionReady;
    private long lastPreparationTick = Long.MIN_VALUE;
    private long lastRenderedFrame = Long.MIN_VALUE;
    private long loadingAnimationStart = -1L;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_TYPE, pos, state);
    }

    public static void tick(Level world, BlockPos pos, BlockState state, ProjectorBlockEntity be) {
        boolean wasActive = be.active;
        be.active = world.isClientSide ? state.getValue(ProjectorBlock.POWERED) : world.hasNeighborSignal(pos);
        if (!world.isClientSide && state.getValue(ProjectorBlock.POWERED) != be.active) {
            world.setBlock(pos, state.setValue(ProjectorBlock.POWERED, be.active), Block.UPDATE_CLIENTS);
        }
        if (wasActive != be.active) {
            ProjectorPowerTrace.changed(world, pos, be.channel, "neighbor-signal", wasActive, be.active, be.getRevealDistance());
            be.setChanged();
        }
        if (!wasActive && be.active) {
            be.revealDistance = -1;
            be.clientRevealDistance = -1;
        }
        be.tickProjection(world);
        if (!world.isClientSide && be.isProjectionVisible()) {
            ChannelManagerPersistence.get(world).recordProjection(be.channel);
        }
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putString("channel", channel);
        tag.putBoolean("active", active);
        tag.putInt("targetDistance", revealDistance);

        super.saveAdditional(tag, registryLookup);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registryLookup) {
        boolean wasActive = active;
        String previousChannel = channel;
        channel = tag.getString("channel");
        active = tag.getBoolean("active");
        revealDistance = tag.contains("targetDistance") ? tag.getInt("targetDistance") : -1;
        if (!wasActive && active || !previousChannel.equals(channel)) {
            clientRevealDistance = -1;
            clientProjectionReady = false;
        }
        if (level != null && wasActive != active) {
            ProjectorPowerTrace.changed(level, worldPosition, channel, "block-entity-sync", wasActive, active, getRevealDistance());
        }

        super.loadAdditional(tag, registryLookup);
    }

    public String getChannel() {
        return channel;
    }

    public void setChannelFromServer(String channel) {
        String value = Objects.requireNonNull(channel);
        if (this.channel.equals(value)) {
            return;
        }
        this.channel = value;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup) {
        return saveWithoutMetadata(registryLookup);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("G.L.A.S.S Projector");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
        return new ProjectorBlockGUI(syncId, inventory, worldPosition);
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, BlockPos> getScreenOpeningCodec() {
        return BlockPos.STREAM_CODEC;
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayer player) {
        return worldPosition;
    }

    private void tickProjection(Level world) {
        if (!active && getRevealDistance() < 0) {
            return;
        }
        Direction facing = getBlockState().getValue(ProjectorBlock.FACING);
        ensureProjectionSurface(world, facing);
        if (world.isClientSide) {
            if (active) {
                if (clientProjectionReady && clientRevealDistance < projectionSurface.completedRevealDistance()) {
                    clientRevealDistance++;
                    playPanelTransitionSounds(world, GSounds.PROJECTION_PANEL_ACTIVATE, clientRevealDistance);
                }
            } else if (clientRevealDistance >= 0) {
                playPanelTransitionSounds(world, GSounds.PROJECTION_PANEL_DEACTIVATE, clientRevealDistance);
                clientRevealDistance--;
            }
            return;
        }
        int oldRevealDistance = revealDistance;
        if (active) {
            revealDistance = Math.min(projectionSurface.completedRevealDistance(), revealDistance + 1);
        } else {
            revealDistance = Math.max(-1, revealDistance - 1);
        }
        if (revealDistance != oldRevealDistance) {
            setChanged();
        }
    }

    private void playPanelTransitionSounds(Level world, SoundEvent sound, int distance) {
        for (ProjectionSurface.Face face : projectionSurface.faces()) {
            if (face.revealDistance() != distance) {
                continue;
            }
            BlockPos position = face.position();
            Direction normal = face.normal();
            world.playLocalSound(
                    position.getX() + 0.5D + normal.getStepX() * 0.5D,
                    position.getY() + 0.5D + normal.getStepY() * 0.5D,
                    position.getZ() + 0.5D + normal.getStepZ() * 0.5D,
                    sound,
                    SoundSource.BLOCKS,
                    0.6F,
                    0.8F + world.random.nextFloat() * 0.4F,
                    false
            );
        }
    }

    private void ensureProjectionSurface(Level world, Direction facing) {
        boolean facingChanged = projectionSurface != null && projectionSurface.facing() != facing;
        ProjectionSurface.BlockQuery query = position -> sampleBlock(world, position);
        if (projectionSurface != null && projectionSurface.isTopologyValid(facing, query)) {
            return;
        }

        ProjectionSurface rebuilt = ProjectionSurface.rebuild(
                worldPosition,
                facing,
                query
        );
        boolean topologyChanged = !rebuilt.equals(projectionSurface);
        projectionSurface = rebuilt;
        if (!topologyChanged) {
            return;
        }
        if (facingChanged) {
            revealDistance = -1;
            clientRevealDistance = -1;
        } else {
            revealDistance = Math.min(revealDistance, rebuilt.completedRevealDistance());
            clientRevealDistance = Math.min(clientRevealDistance, rebuilt.completedRevealDistance());
        }
        setChanged();
    }

    private static ProjectionSurface.BlockSample sampleBlock(Level world, BlockPos position) {
        int chunkX = SectionPos.blockToSectionCoord(position.getX());
        int chunkZ = SectionPos.blockToSectionCoord(position.getZ());
        if (!world.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return ProjectionSurface.BlockSample.UNLOADED;
        }
        return world.getBlockState(position).is(GBlocks.POWERABLE_GLASS)
                ? ProjectionSurface.BlockSample.GLASS
                : ProjectionSurface.BlockSample.OTHER;
    }

    @Nullable
    public ProjectionSurface getProjectionSurface() {
        return projectionSurface;
    }

    public void prepareProjectionSurface() {
        if (level != null) {
            long tick = level.getGameTime();
            if (projectionSurface != null && projectionSurface.facing() == getBlockState().getValue(ProjectorBlock.FACING)
                    && lastPreparationTick != Long.MIN_VALUE && tick - lastPreparationTick < 10L) {
                return;
            }
            lastPreparationTick = tick;
            ensureProjectionSurface(level, getBlockState().getValue(ProjectorBlock.FACING));
        }
    }

    public boolean claimRenderFrame(long frame) {
        if (lastRenderedFrame == frame) {
            return false;
        }
        lastRenderedFrame = frame;
        return true;
    }

    public long loadingAnimationStart(long now) {
        if (loadingAnimationStart < 0L) {
            loadingAnimationStart = now;
        }
        return loadingAnimationStart;
    }

    public void stopLoadingAnimation() {
        loadingAnimationStart = -1L;
    }

    public void setClientProjectionReady(boolean ready) {
        clientProjectionReady = ready;
    }

    public int getRevealDistance() {
        return level != null && level.isClientSide ? clientRevealDistance : revealDistance;
    }

    public boolean isProjectionVisible() {
        return projectionSurface != null && (active || getRevealDistance() >= 0);
    }

    public boolean isActive() {
        return active;
    }
}
