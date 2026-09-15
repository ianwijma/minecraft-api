package dev.example.mapi.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathPlannerTest {

    /** Fake dispatch: moves the player toward the target at a fixed speed. */
    private static final class FakeMovement implements PathPlanner.MovementDispatch {
        double x;
        double z;
        int dispatches;

        FakeMovement(double x, double z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean dispatch(double yawDelta, int ticks) {
            dispatches++;
            // Move in the facing direction (yaw 0 = +Z, yaw 180 = -Z, etc.)
            // Speed: 0.05 blocks/tick × 20 ticks = 1 block per dispatch —
            // converges without overshooting the 1-block arrival threshold.
            double rad = Math.toRadians(yawDelta);
            x += -Math.sin(rad) * 1.0;
            z += Math.cos(rad) * 1.0;
            return true;
        }

        @Override
        public double[] playerPosition() {
            return new double[] {x, z};
        }
    }

    /** Stuck movement: always reports the same position. */
    private static final class StuckMovement implements PathPlanner.MovementDispatch {
        @Override
        public boolean dispatch(double yawDelta, int ticks) {
            return true;
        }

        @Override
        public double[] playerPosition() {
            return new double[] {100.0, 100.0};
        }
    }

    @Test
    void plannerArrivesAtTargetOnOpenTerrain() {
        // Start at (0,0), target (10, 0) — walk east. The fake moves toward
        // where the yaw points.
        FakeMovement movement = new FakeMovement(0, 0);
        PathPlanner planner = new PathPlanner(movement);
        // The yaw computation: atan2(-(10-0), 0-0) = atan2(-10, 0) = -90°
        // The fake movement function will move based on the yaw.
        PathPlanner.PathResult result = planner.execute(0, 0, 10, 0,
                new PathPlanner.Params(20, 20, 1.0, 0.1));
        assertTrue(result.arrived(), "should arrive: " + result.finalDistance());
        assertTrue(result.legsExecuted() > 0);
        assertTrue(!result.stuck());
        assertTrue(movement.dispatches > 0);
    }

    @Test
    void plannerStopsOnStuck() {
        PathPlanner planner = new PathPlanner(new StuckMovement());
        PathPlanner.PathResult result = planner.execute(100, 100, 200, 200,
                PathPlanner.Params.DEFAULT);
        assertTrue(!result.arrived());
        assertTrue(result.stuck());
        assertTrue(result.legsExecuted() < 20, "stopped early");
        assertEquals(0, result.legs().get(0).distance(), 0.01);
        assertTrue(result.legs().get(0).stuck());
    }

    @Test
    void alreadyAtTargetArrivesImmediately() {
        PathPlanner planner = new PathPlanner(new FakeMovement(5, 5));
        PathPlanner.PathResult result = planner.execute(5, 5, 5.5, 5.5,
                new PathPlanner.Params(10, 20, 1.0, 0.1));
        assertTrue(result.arrived());
        assertEquals(0, result.legsExecuted());
    }

    @Test
    void legDistanceIsAccurate() {
        // Dispatch that moves exactly 1 block per call.
        PathPlanner.MovementDispatch oneBlock = new PathPlanner.MovementDispatch() {
            @Override
            public boolean dispatch(double yawDelta, int ticks) {
                return true;
            }

            @Override
            public double[] playerPosition() {
                return new double[] {101.0, 100.0}; // 1 block east
            }
        };
        PathPlanner planner = new PathPlanner(oneBlock);
        PathPlanner.PathResult result = planner.execute(100, 100, 102, 100,
                new PathPlanner.Params(10, 20, 0.5, 0.05));
        assertEquals(1.0, result.legs().get(0).distance(), 0.01);
    }
}
