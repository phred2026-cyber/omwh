package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public final class HomeDestination {
    enum Decision { ACCEPT, NO_HOME, CROSS_DIMENSION }
    enum Outcome { ACCEPT, NO_HOME, CROSS_DIMENSION, VEHICLE_TOO_LARGE, UNSAFE }
    record Result(Outcome outcome, DestinationSafety.Prepared destination) { }

    private HomeDestination() { }

    static Decision decide(boolean missingRespawnBlock, boolean sameDimension) {
        if (missingRespawnBlock) return Decision.NO_HOME;
        if (!sameDimension) return Decision.CROSS_DIMENSION;
        return Decision.ACCEPT;
    }

    static boolean mayTryAboveBed(boolean mounted, boolean bed, boolean forced, boolean covered) {
        return mounted && bed && !forced && !covered;
    }

    static Result find(ServerPlayer player) {
        var respawnConfig = player.getRespawnConfig();
        if (respawnConfig == null) return new Result(Outcome.NO_HOME, null);

        // false preserves respawn-anchor charges; vanilla remains the authority for respawn placement.
        TeleportTransition respawn = player.findRespawnPositionAndUseSpawnBlock(
                false, TeleportTransition.DO_NOTHING);
        if (!(player.level() instanceof ServerLevel current)) return new Result(Outcome.UNSAFE, null);
        Decision decision = decide(respawn.missingRespawnBlock(),
                current.dimension().equals(respawn.newLevel().dimension()));
        if (decision == Decision.NO_HOME) return new Result(Outcome.NO_HOME, null);
        if (decision == Decision.CROSS_DIMENSION) return new Result(Outcome.CROSS_DIMENSION, null);

        Entity root = player.getRootVehicle();
        if (root == player) {
            return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(respawn.newLevel(),
                    respawn.position(), respawn.yRot(), respawn.xRot()));
        }

        BlockPos homeBlock = respawnConfig.respawnData().pos();
        if (DestinationSafety.mountedHomeFits(root, respawn.newLevel(), respawn.position(), homeBlock)) {
            return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(respawn.newLevel(),
                    respawn.position(), root.getYRot(), root.getXRot()));
        }

        boolean bed = respawn.newLevel().getBlockState(homeBlock).getBlock() instanceof BedBlock;
        boolean covered = bed && isCoveredBed(respawn.newLevel(), homeBlock);
        if (mayTryAboveBed(true, bed, respawnConfig.forced(), covered)) {
            Vec3 aboveBed = new Vec3(homeBlock.getX() + 0.5, homeBlock.getY() + 1.0,
                    homeBlock.getZ() + 0.5);
            if (DestinationSafety.mountedHomeFits(root, respawn.newLevel(), aboveBed, homeBlock)) {
                return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(respawn.newLevel(),
                        aboveBed, root.getYRot(), root.getXRot()));
            }
        }
        return new Result(Outcome.VEHICLE_TOO_LARGE, null);
    }

    private static boolean isCoveredBed(ServerLevel level, BlockPos homeBlock) {
        var homeState = level.getBlockState(homeBlock);
        BlockPos other = homeBlock.relative(BedBlock.getConnectedDirection(homeState));
        return !level.getBlockState(homeBlock.above()).getCollisionShape(level, homeBlock.above()).isEmpty()
                || !level.getBlockState(other.above()).getCollisionShape(level, other.above()).isEmpty();
    }
}
