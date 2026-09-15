package dev.example.mapi.runner;

import java.util.ArrayList;
import java.util.List;

/**
 * Basic loaded-world path planning (spec §16: required runner). Plans
 * straight-line waypoint legs toward a target, dispatches them through the
 * movement API, and implements stop-on-stuck: when the position delta after
 * a leg falls below a threshold, the planner stops and reports the stall.
 *
 * <p>No hazard-aware replanning (spec §16: Extension/Future). No pathfinding
 * around obstacles — straight-line legs only.
 */
final class PathPlanner {

    /** A 2D target (yaw is computed from the position delta). */
    record Target(double x, double z) {
    }

    /** One executed leg with its observed movement. */
    record Leg(int index, double startX, double startZ, double endX, double endZ,
            double distance, boolean stuck) {

        /** @return the leg as an ordered map for JSON serialization */
        java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("index", index);
            map.put("startX", startX);
            map.put("startZ", startZ);
            map.put("endX", endX);
            map.put("endZ", endZ);
            map.put("distance", distance);
            map.put("stuck", stuck);
            return map;
        }
    }

    /** Result of a planned path execution. */
    record PathResult(double startX, double startZ, double targetX, double targetZ,
            int legsExecuted, boolean arrived, boolean stuck, double finalDistance,
            List<Leg> legs) {

        /** @return the result as an ordered map for JSON serialization */
        java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("startX", startX);
            map.put("startZ", startZ);
            map.put("targetX", targetX);
            map.put("targetZ", targetZ);
            map.put("legsExecuted", legsExecuted);
            map.put("arrived", arrived);
            map.put("stuck", stuck);
            map.put("finalDistance", finalDistance);
            map.put("legs", legs.stream().map(Leg::toMap).toList());
            return map;
        }
    }

    /** Dispatches a single leg: turn + hold forward for N ticks. */
    interface MovementDispatch {

        /**
         * @param yawDelta relative yaw turn in degrees
         * @param ticks    forward-hold duration in client ticks
         * @return the leg was dispatched
         */
        boolean dispatch(double yawDelta, int ticks);

        /**
         * @return {@code [x, z]} of the player after the leg
         */
        double[] playerPosition();
    }

    /** Planning parameters. */
    record Params(int maxLegs, int ticksPerLeg, double arrivalDistance,
            double stuckThreshold) {

        /** Default parameters: 20 legs × 20 ticks, arrive within 1 block, stuck below 0.1. */
        public static final Params DEFAULT = new Params(20, 20, 1.0, 0.1);

        public Params {
            if (maxLegs < 1 || maxLegs > 100) {
                throw new IllegalArgumentException("maxLegs must be 1..100");
            }
            if (ticksPerLeg < 1 || ticksPerLeg > 3600) {
                throw new IllegalArgumentException("ticksPerLeg must be 1..3600");
            }
            if (arrivalDistance <= 0) {
                throw new IllegalArgumentException("arrivalDistance must be positive");
            }
            if (stuckThreshold < 0) {
                throw new IllegalArgumentException("stuckThreshold must not be negative");
            }
        }
    }

    private final MovementDispatch dispatch;

    PathPlanner(MovementDispatch dispatch) {
        this.dispatch = java.util.Objects.requireNonNull(dispatch, "dispatch");
    }

    /**
     * Plans and executes a path toward the target.
     *
     * @param startX  player X at start
     * @param startZ  player Z at start
     * @param targetX target X
     * @param targetZ target Z
     * @param params  planning parameters
     * @return the path result with per-leg data
     */
    PathResult execute(double startX, double startZ, double targetX, double targetZ,
            Params params) {
        double px = startX;
        double pz = startZ;
        List<Leg> legs = new ArrayList<>();
        boolean stuck = false;
        boolean arrived = false;
        int executed = 0;

        for (int i = 0; i < params.maxLegs(); i++) {
            double dx = targetX - px;
            double dz = targetZ - pz;
            double distance = Math.sqrt(dx * dx + dz * dz);

            if (distance <= params.arrivalDistance()) {
                arrived = true;
                break;
            }

            // Compute the yaw delta needed to face the target (Minecraft yaw:
            // 0 = +Z, 90 = -X, 180 = -Z, 270 = +X). Relative delta from the
            // current facing is what mouseDelta expects.
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double yawDelta = targetYaw; // relative: the game applies it

            dispatch.dispatch(yawDelta, params.ticksPerLeg());

            double[] pos = dispatch.playerPosition();
            double nx = pos[0];
            double nz = pos[1];
            double moved = Math.sqrt((nx - px) * (nx - px) + (nz - pz) * (nz - pz));

            boolean isStuck = moved < params.stuckThreshold();
            legs.add(new Leg(i, px, pz, nx, nz, moved, isStuck));
            executed++;

            if (isStuck) {
                stuck = true;
                break;
            }

            px = nx;
            pz = nz;
        }

        double finalDx = targetX - px;
        double finalDz = targetZ - pz;
        double finalDistance = Math.sqrt(finalDx * finalDx + finalDz * finalDz);
        if (!stuck && finalDistance <= params.arrivalDistance()) {
            arrived = true;
        }
        return new PathResult(startX, startZ, targetX, targetZ, executed,
                arrived, stuck, finalDistance, List.copyOf(legs));
    }

    /** Computes the yaw needed to face from (px, pz) toward (tx, tz). */
    static double yawToward(double px, double pz, double tx, double tz) {
        double dx = tx - px;
        double dz = tz - pz;
        return Math.toDegrees(Math.atan2(-dx, dz));
    }
}
