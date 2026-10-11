package dev.imb11.mixins.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.imb11.client.compat.IrisCompatibility;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.shaderpack.materialmap.BlockRenderType;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings", remap = false)
abstract class IrisWorldRenderingSettingsMixin {
    @Unique
    private WorldRenderingSettings glass$projectionSettings() {
        return (Object) this == WorldRenderingSettings.INSTANCE
                ? (WorldRenderingSettings) IrisCompatibility.worldSettings() : null;
    }

    @WrapMethod(method = "isReloadRequired")
    private boolean glass$isReloadRequired(Operation<Boolean> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.isReloadRequired();
    }

    @WrapMethod(method = "clearReloadRequired")
    private void glass$clearReloadRequired(Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call();
        } else {
            settings.clearReloadRequired();
        }
    }

    @WrapMethod(method = "getBlockStateIds")
    private Object2IntMap<BlockState> glass$getBlockStateIds(Operation<Object2IntMap<BlockState>> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.getBlockStateIds();
    }

    @WrapMethod(method = "setBlockStateIds")
    private void glass$setBlockStateIds(Object2IntMap<BlockState> ids, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(ids);
        } else {
            settings.setBlockStateIds(ids);
        }
    }

    @WrapMethod(method = "getBlockTypeIds")
    private Map<Block, BlockRenderType> glass$getBlockTypeIds(Operation<Map<Block, BlockRenderType>> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.getBlockTypeIds();
    }

    @WrapMethod(method = "setBlockTypeIds")
    private void glass$setBlockTypeIds(Map<Block, BlockRenderType> ids, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(ids);
        } else {
            settings.setBlockTypeIds(ids);
        }
    }

    @WrapMethod(method = "getEntityIds")
    private Object2IntFunction<NamespacedId> glass$getEntityIds(Operation<Object2IntFunction<NamespacedId>> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.getEntityIds();
    }

    @WrapMethod(method = "setEntityIds")
    private void glass$setEntityIds(Object2IntFunction<NamespacedId> ids, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(ids);
        } else {
            settings.setEntityIds(ids);
        }
    }

    @WrapMethod(method = "getItemIds")
    private Object2IntFunction<NamespacedId> glass$getItemIds(Operation<Object2IntFunction<NamespacedId>> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.getItemIds();
    }

    @WrapMethod(method = "setItemIds")
    private void glass$setItemIds(Object2IntFunction<NamespacedId> ids, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(ids);
        } else {
            settings.setItemIds(ids);
        }
    }

    @WrapMethod(method = "getAmbientOcclusionLevel")
    private float glass$getAmbientOcclusionLevel(Operation<Float> original) {
        if (IrisCompatibility.isUsingVanillaVertices()
                || ProjectionRenderContext.isActive() && IrisCompatibility.shaderView() == null) {
            return 1.0F;
        }
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.getAmbientOcclusionLevel();
    }

    @WrapMethod(method = "setAmbientOcclusionLevel")
    private void glass$setAmbientOcclusionLevel(float level, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(level);
        } else {
            settings.setAmbientOcclusionLevel(level);
        }
    }

    @WrapMethod(method = "shouldDisableDirectionalShading")
    private boolean glass$shouldDisableDirectionalShading(Operation<Boolean> original) {
        if (IrisCompatibility.isUsingVanillaVertices()
                || ProjectionRenderContext.isActive() && IrisCompatibility.shaderView() == null) {
            return false;
        }
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.shouldDisableDirectionalShading();
    }

    @WrapMethod(method = "setDisableDirectionalShading")
    private void glass$setDisableDirectionalShading(boolean disabled, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(disabled);
        } else {
            settings.setDisableDirectionalShading(disabled);
        }
    }

    @WrapMethod(method = "shouldUseSeparateAo")
    private boolean glass$shouldUseSeparateAo(Operation<Boolean> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.shouldUseSeparateAo();
    }

    @WrapMethod(method = "setUseSeparateAo")
    private void glass$setUseSeparateAo(boolean separate, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(separate);
        } else {
            settings.setUseSeparateAo(separate);
        }
    }

    @WrapMethod(method = "getVertexFormat")
    private ChunkVertexType glass$getVertexFormat(Operation<ChunkVertexType> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.getVertexFormat();
    }

    @WrapMethod(method = "setVertexFormat")
    private void glass$setVertexFormat(ChunkVertexType format, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(format);
        } else {
            settings.setVertexFormat(format);
        }
    }

    @WrapMethod(method = "shouldVoxelizeLightBlocks")
    private boolean glass$shouldVoxelizeLightBlocks(Operation<Boolean> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.shouldVoxelizeLightBlocks();
    }

    @WrapMethod(method = "setVoxelizeLightBlocks")
    private void glass$setVoxelizeLightBlocks(boolean voxelize, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(voxelize);
        } else {
            settings.setVoxelizeLightBlocks(voxelize);
        }
    }

    @WrapMethod(method = "shouldSeparateEntityDraws")
    private boolean glass$shouldSeparateEntityDraws(Operation<Boolean> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.shouldSeparateEntityDraws();
    }

    @WrapMethod(method = "setSeparateEntityDraws")
    private void glass$setSeparateEntityDraws(boolean separate, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(separate);
        } else {
            settings.setSeparateEntityDraws(separate);
        }
    }

    @WrapMethod(method = "hasVillagerConversionId")
    private boolean glass$hasVillagerConversionId(Operation<Boolean> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.hasVillagerConversionId();
    }

    @WrapMethod(method = "setBreaksAnisotropy")
    private void glass$setBreaksAnisotropy(boolean breaks, Operation<Void> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        if (settings == null) {
            original.call(breaks);
        } else {
            settings.setBreaksAnisotropy(breaks);
        }
    }

    @WrapMethod(method = "breaksAnisotropy")
    private boolean glass$breaksAnisotropy(Operation<Boolean> original) {
        WorldRenderingSettings settings = glass$projectionSettings();
        return settings == null ? original.call() : settings.breaksAnisotropy();
    }
}
