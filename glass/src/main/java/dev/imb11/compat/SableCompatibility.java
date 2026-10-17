package dev.imb11.compat;

import dev.imb11.platform.Platform;
import dev.imb11.sync.SourcePose;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

public final class SableCompatibility {
    private static final boolean LOADED = Platform.isModLoaded("sable");

    private SableCompatibility() {
    }

    public static boolean isOnSubLevel(Level level, BlockPos pos) {
        return LOADED && SableAccess.isOnSubLevel(level, pos);
    }

    @Nullable
    public static SourcePose sourcePose(Level level, BlockPos pos) {
        return LOADED ? SableAccess.sourcePose(level, pos) : null;
    }

    public static Vec3 toLocalPosition(Level level, BlockPos anchor, Vec3 position) {
        return LOADED ? SableAccess.toLocalPosition(level, anchor, position) : position;
    }

    public static Quaternionf toLocalRotation(Level level, BlockPos anchor, Quaternionf rotation) {
        return LOADED ? SableAccess.toLocalRotation(level, anchor, rotation) : rotation;
    }

    public static AABB toWorldBounds(Level level, BlockPos anchor, AABB bounds) {
        return LOADED ? SableAccess.toWorldBounds(level, anchor, bounds) : bounds;
    }

    private static final class SableAccess {
        private static boolean isOnSubLevel(Level level, BlockPos pos) {
            return SableCompanion.INSTANCE.isInPlotGrid(level, pos);
        }

        @Nullable
        private static Pose3dc pose(Level level, BlockPos anchor) {
            SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, anchor);
            if (subLevel == null) {
                return null;
            }
            return subLevel instanceof ClientSubLevelAccess clientSubLevel
                    ? clientSubLevel.renderPose()
                    : subLevel.logicalPose();
        }

        @Nullable
        private static SourcePose sourcePose(Level level, BlockPos pos) {
            Pose3dc pose = pose(level, pos);
            if (pose == null) {
                return null;
            }
            return new SourcePose(pose.transformPosition(Vec3.atCenterOf(pos)), new Quaternionf(pose.orientation()));
        }

        private static Vec3 toLocalPosition(Level level, BlockPos anchor, Vec3 position) {
            Pose3dc pose = pose(level, anchor);
            return pose == null ? position : pose.transformPositionInverse(position);
        }

        private static Quaternionf toLocalRotation(Level level, BlockPos anchor, Quaternionf rotation) {
            Pose3dc pose = pose(level, anchor);
            if (pose == null) {
                return rotation;
            }
            return new Quaternionf(pose.orientation()).conjugate().mul(rotation).normalize();
        }

        private static AABB toWorldBounds(Level level, BlockPos anchor, AABB bounds) {
            Pose3dc pose = pose(level, anchor);
            if (pose == null) {
                return bounds;
            }
            AABB transformed = null;
            for (int corner = 0; corner < 8; corner++) {
                Vec3 point = pose.transformPosition(new Vec3(
                        (corner & 1) == 0 ? bounds.minX : bounds.maxX,
                        (corner & 2) == 0 ? bounds.minY : bounds.maxY,
                        (corner & 4) == 0 ? bounds.minZ : bounds.maxZ
                ));
                AABB pointBounds = new AABB(point, point);
                transformed = transformed == null ? pointBounds : transformed.minmax(pointBounds);
            }
            return transformed;
        }
    }
}
