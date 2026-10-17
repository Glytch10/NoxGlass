package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.imb11.mixins.BufferSourceAccessor;
import dev.imb11.mixins.LevelRendererBufferAccessor;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class RenderResources {
    private RenderResources() {
    }

    public static void runOnRenderThread(Runnable operation) {
        if (RenderSystem.isOnRenderThread()) {
            operation.run();
        } else {
            RenderSystem.recordRenderCall(operation::run);
        }
    }

    public static void closeGlobalBuffers(LevelRenderer renderer) {
        LevelRendererBufferAccessor buffers = (LevelRendererBufferAccessor) renderer;
        for (VertexBuffer buffer : new VertexBuffer[]{buffers.glass$getStarBuffer(), buffers.glass$getSkyBuffer(),
                buffers.glass$getDarkBuffer(), buffers.glass$getCloudBuffer()}) {
            if (buffer != null) {
                buffer.close();
            }
        }
    }

    public static void closeBufferBuilders(RenderBuffers renderBuffers) {
        Set<ByteBufferBuilder> builders = Collections.newSetFromMap(new IdentityHashMap<>());
        SectionBufferBuilderPack fixed = renderBuffers.fixedBufferPack();
        for (RenderType renderType : RenderType.chunkBufferLayers()) {
            builders.add(fixed.buffer(renderType));
        }
        addBuilders(builders, renderBuffers.bufferSource());
        addBuilders(builders, renderBuffers.crumblingBufferSource());
        builders.forEach(ByteBufferBuilder::close);
    }

    public static void closeAvailablePoolBuffers(SectionBufferBuilderPool pool) {
        SectionBufferBuilderPack pack;
        while ((pack = pool.acquire()) != null) {
            pack.close();
        }
    }

    private static void addBuilders(Set<ByteBufferBuilder> builders, MultiBufferSource.BufferSource source) {
        BufferSourceAccessor accessor = (BufferSourceAccessor) source;
        builders.add(accessor.glass$getSharedBuffer());
        builders.addAll(accessor.glass$getFixedBuffers().values());
    }
}
