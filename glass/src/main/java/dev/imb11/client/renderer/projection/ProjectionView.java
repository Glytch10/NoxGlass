package dev.imb11.client.renderer.projection;

import dev.imb11.projection.ProjectionSurface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

record ProjectionView(
        ResourceKey<Level> dimension,
        BlockPos projectorPos,
        BlockPos origin,
        Direction facing,
        Direction uDirection,
        Direction vDirection,
        List<ProjectionSurface.Face> faces,
        AABB bounds
) {
    private static final double PROJECTOR_PLANE_OFFSET = 0.502D;

    ProjectionView {
        projectorPos = projectorPos.immutable();
        origin = origin.immutable();
    }

    static ProjectionView create(ResourceKey<Level> dimension, BlockPos projectorPos, ProjectionSurface surface) {
        return new ProjectionView(dimension, projectorPos, surface.origin(), surface.facing(), surface.uDirection(),
                surface.vDirection(), surface.faces(), surface.renderBounds());
    }

    boolean sameFrame(ProjectionView other) {
        return facing == other.facing && origin.equals(other.origin);
    }

    Vec3 normal() {
        return Vec3.atLowerCornerOf(facing.getNormal());
    }

    boolean planar() {
        if (faces.isEmpty()) {
            return true;
        }
        Direction.Axis axis = facing.getAxis();
        int plane = faces.getFirst().position().get(axis);
        for (ProjectionSurface.Face face : faces) {
            if (face.normal() != facing || face.position().get(axis) != plane) {
                return false;
            }
        }
        return true;
    }

    Vec3 projectorAnchor() {
        return Vec3.atCenterOf(projectorPos).add(normal().scale(PROJECTOR_PLANE_OFFSET));
    }
}
