package dev.imb11.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.imb11.platform.Platform;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class SableRenderCompatibility {
    private static final boolean LOADED = Platform.isModLoaded("sable");

    private SableRenderCompatibility() {
    }

    public static void renderSectionLayer(ClientLevel level, RenderType layer, ShaderInstance shader, Vec3 camera,
                                          Matrix4f modelView, Matrix4f projection, float partialTick) {
        if (LOADED) {
            SableAccess.renderSectionLayer(level, layer, shader, camera, modelView, projection, partialTick);
        }
    }

    public static void renderAfterSections(ClientLevel level, Vec3 camera, Matrix4f modelView, Matrix4f projection, float partialTick) {
        if (LOADED) {
            SableAccess.renderAfterSections(level, camera, modelView, projection, partialTick);
        }
    }

    public static void renderBlockEntities(ClientLevel level, BlockEntityRenderDispatcher dispatcher, MultiBufferSource buffers,
                                           Vec3 camera, float partialTick, Predicate<BlockEntity> hidden) {
        if (LOADED) {
            SableAccess.renderBlockEntities(level, dispatcher, buffers, camera, partialTick, hidden);
        }
    }

    private static final class SableAccess {
        private static List<ClientSubLevel> subLevels(ClientLevel level) {
            ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
            return container == null ? List.of() : container.getAllSubLevels();
        }

        private static void renderSectionLayer(ClientLevel level, RenderType layer, ShaderInstance shader, Vec3 camera,
                                               Matrix4f modelView, Matrix4f projection, float partialTick) {
            List<ClientSubLevel> subLevels = subLevels(level);
            if (!subLevels.isEmpty()) {
                SubLevelRenderDispatcher.get().renderSectionLayer(subLevels, layer, shader, camera.x, camera.y, camera.z,
                        modelView, projection, partialTick);
            }
        }

        private static void renderAfterSections(ClientLevel level, Vec3 camera, Matrix4f modelView, Matrix4f projection, float partialTick) {
            List<ClientSubLevel> subLevels = subLevels(level);
            if (subLevels.isEmpty()) {
                return;
            }
            SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
            Collection<RenderType> pending = pendingSingleBlockLayers(dispatcher);
            if (pending == null) {
                return;
            }
            List<RenderType> mainFrameLayers = List.copyOf(pending);
            try {
                dispatcher.renderAfterSections(subLevels, camera.x, camera.y, camera.z, modelView, projection, partialTick);
            } finally {
                pending.addAll(mainFrameLayers);
            }
        }

        @Nullable
        @SuppressWarnings("unchecked")
        private static Collection<RenderType> pendingSingleBlockLayers(SubLevelRenderDispatcher dispatcher) {
            for (Class<?> type = dispatcher.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField("singleBlockLayers");
                    field.setAccessible(true);
                    return field.get(dispatcher) instanceof Collection<?> layers ? (Collection<RenderType>) layers : null;
                } catch (NoSuchFieldException ignored) {
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    return null;
                }
            }
            return null;
        }

        private static void renderBlockEntities(ClientLevel level, BlockEntityRenderDispatcher dispatcher, MultiBufferSource buffers,
                                                Vec3 camera, float partialTick, Predicate<BlockEntity> hidden) {
            List<ClientSubLevel> subLevels = subLevels(level);
            if (!subLevels.isEmpty()) {
                SubLevelRenderDispatcher.get().renderBlockEntities(subLevels, new FilteredBlockEntityRenderer(dispatcher, buffers, hidden),
                        camera.x, camera.y, camera.z, partialTick);
            }
        }
    }

    private record FilteredBlockEntityRenderer(
            BlockEntityRenderDispatcher dispatcher,
            MultiBufferSource buffers,
            Predicate<BlockEntity> hidden
    ) implements SubLevelRenderDispatcher.BlockEntityRenderer {
        @Override
        public BlockEntityRenderDispatcher getBlockEntityRenderDispatcher() {
            return dispatcher;
        }

        @Override
        public void renderSingleBE(BlockEntity blockEntity, PoseStack poseStack, float partialTick,
                                   double cameraX, double cameraY, double cameraZ) {
            if (blockEntity.isRemoved() || hidden.test(blockEntity)) {
                return;
            }
            BlockPos pos = blockEntity.getBlockPos();
            poseStack.pushPose();
            try {
                poseStack.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);
                dispatcher.render(blockEntity, partialTick, poseStack, buffers);
            } finally {
                poseStack.popPose();
            }
        }
    }
}
