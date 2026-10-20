package dev.imb11.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

public final class ProjectorPowerTrace {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ProjectorPowerTrace() {
    }

    public static void changed(Level level, BlockPos pos, String channel, String cause, boolean wasActive, boolean active,
                               int revealDistance) {
        LOGGER.info("[GLASS projector] power side={} dimension={} pos={} channel={} cause={} active={}->{} reveal={} tick={}",
                level.isClientSide ? "client" : "server", level.dimension().location(), pos.toShortString(),
                channel, cause, wasActive, active, revealDistance, level.getGameTime());
    }
}
