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
    enum MountedChoice { EXACT, ABOVE_BED, VEHICLE_TOO_LARGE, UNSAFE }
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

    static boolean mayUseRespawn(boolean safe, boolean force) {
        return safe || force;
    }

    static MountedChoice chooseMounted(DestinationSafety.HomeFit exact, boolean mayTryFallback,
                                       DestinationSafety.HomeFit fallback) {
        if (exact == DestinationSafety.HomeFit.FITS) return MountedChoice.EXACT;
        if (exact == DestinationSafety.HomeFit.UNSAFE) return MountedChoice.UNSAFE;
        if (!mayTryFallback) return MountedChoice.VEHICLE_TOO_LARGE;
        if (fallback == DestinationSafety.HomeFit.FITS) return MountedChoice.ABOVE_BED;
        if (fallback == DestinationSafety.HomeFit.UNSAFE) return MountedChoice.UNSAFE;
        return MountedChoice.VEHICLE_TOO_LARGE;
    }

    static Result find(ServerPlayer player, boolean force) {
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
            if (!mayUseRespawn(DestinationSafety.unmountedHomeFits(
                    player, respawn.newLevel(), respawn.position()), force)) {
                return new Result(Outcome.UNSAFE, null);
            }
            return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(respawn.newLevel(),
                    respawn.position(), respawn.yRot(), respawn.xRot()));
        }

        if (force) {
            return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(respawn.newLevel(),
                    respawn.position(), root.getYRot(), root.getXRot()));
        }

        BlockPos homeBlock = respawnConfig.respawnData().pos();
        DestinationSafety.HomeFit exactFit = DestinationSafety.mountedHomeFit(
                root, respawn.newLevel(), respawn.position(), homeBlock);
        boolean bed = respawn.newLevel().getBlockState(homeBlock).getBlock() instanceof BedBlock;
        boolean covered = bed && isCoveredBed(respawn.newLevel(), homeBlock);
        boolean mayTryFallback = mayTryAboveBed(true, bed, respawnConfig.forced(), covered);
        Vec3 aboveBed = new Vec3(homeBlock.getX() + 0.5, homeBlock.getY() + 1.0,
                homeBlock.getZ() + 0.5);
        DestinationSafety.HomeFit fallbackFit = mayTryFallback
                ? DestinationSafety.mountedHomeFit(root, respawn.newLevel(), aboveBed, homeBlock)
                : DestinationSafety.HomeFit.BLOCKED;
        MountedChoice choice = chooseMounted(exactFit, mayTryFallback, fallbackFit);
        if (choice == MountedChoice.UNSAFE) return new Result(Outcome.UNSAFE, null);
        if (choice == MountedChoice.VEHICLE_TOO_LARGE) {
            return new Result(Outcome.VEHICLE_TOO_LARGE, null);
        }
        Vec3 position = choice == MountedChoice.ABOVE_BED ? aboveBed : respawn.position();
        return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(respawn.newLevel(),
                position, root.getYRot(), root.getXRot()));
    }

    private static boolean isCoveredBed(ServerLevel level, BlockPos homeBlock) {
        var homeState = level.getBlockState(homeBlock);
        BlockPos other = homeBlock.relative(BedBlock.getConnectedDirection(homeState));
        return !level.getBlockState(homeBlock.above()).getCollisionShape(level, homeBlock.above()).isEmpty()
                || !level.getBlockState(other.above()).getCollisionShape(level, other.above()).isEmpty();
    }
}
