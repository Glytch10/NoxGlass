package dev.imb11.client;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

@FunctionalInterface
public interface ShaderFactory {
    ShaderInstance create(ResourceLocation id, VertexFormat format) throws IOException;
}
