package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.ecs.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for V2.5 event-driven tick gating via CompletableFuture.
 *
 * <p>World.startAsyncOp() returns a CompletableFuture that the MC boundary layer
 * completes when the async MC-level operation finishes. The engine tick is gated
 * (Wandscape.onServerTick skips world.tick()) until all futures resolve.
 */
public class AsyncTickGatingTest {

    // ===================================================================
    // 1. Basic lifecycle — single future
    // ===================================================================

    @Nested
    class SingleFutureTests {
        private World world;

        @BeforeEach
        void setUp() {
            world = new World();
        }

        @Test
        void freshWorld_hasNoPendingOps() {
            assertFalse(world.hasPendingAsyncOps(),
                    "Fresh world should have no pending async ops");
        }

        @Test
        void startAsyncOp_makesPendingTrue() {
            world.startAsyncOp("test_op");
            assertTrue(world.hasPendingAsyncOps(),
                    "After startAsyncOp, hasPendingAsyncOps should be true");
        }

        @Test
        void complete_resolvesAsyncOp() {
            CompletableFuture<Void> future = world.startAsyncOp("test_op");

            assertTrue(world.hasPendingAsyncOps());
            future.complete(null);

            // whenComplete callback runs synchronously on the completing thread
            assertFalse(world.hasPendingAsyncOps(),
                    "After complete(null), pending list should be empty");
        }

        @Test
        void completeExceptionally_resolvesAsyncOp() {
            CompletableFuture<Void> future = world.startAsyncOp("test_op");

            assertTrue(world.hasPendingAsyncOps());
            future.completeExceptionally(new RuntimeException("NPC died mid-op"));

            assertFalse(world.hasPendingAsyncOps(),
                    "After completeExceptionally, pending list should still be emptied");
        }

        @Test
        void cancel_resolvesAsyncOp() {
            CompletableFuture<Void> future = world.startAsyncOp("test_op");

            assertTrue(world.hasPendingAsyncOps());
            future.cancel(true);

            // cancel triggers completeExceptionally internally → whenComplete fires
            assertFalse(world.hasPendingAsyncOps(),
                    "After cancel, pending list should be empty");
        }

        @Test
        void timeout_resolvesAsyncOp() {
            CompletableFuture<Void> future = world.startAsyncOp("test_op");
            future.orTimeout(1, TimeUnit.MILLISECONDS);

            // Wait for timeout to fire
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            assertFalse(world.hasPendingAsyncOps(),
                    "After timeout, pending list should be empty");
        }
    }

    // ===================================================================
    // 2. Multiple concurrent futures — Promise.all() semantics
    // ===================================================================

    @Nested
    class MultipleFutureTests {
        private World world;

        @BeforeEach
        void setUp() {
            world = new World();
        }

        @Test
        void threeFutures_allMustComplete() {
            CompletableFuture<Void> f1 = world.startAsyncOp("move_npc1");
            CompletableFuture<Void> f2 = world.startAsyncOp("move_npc2");
            CompletableFuture<Void> f3 = world.startAsyncOp("move_npc3");

            assertTrue(world.hasPendingAsyncOps());

            f1.complete(null);
            assertTrue(world.hasPendingAsyncOps(), "Gate should still be closed (2 futures remaining)");

            f2.complete(null);
            assertTrue(world.hasPendingAsyncOps(), "Gate should still be closed (1 future remaining)");

            f3.complete(null);
            assertFalse(world.hasPendingAsyncOps(), "All resolved → gate open");
        }

        @Test
        void mixedResolutions_allCompleteThenGateOpens() {
            CompletableFuture<Void> f1 = world.startAsyncOp("op_a");
            CompletableFuture<Void> f2 = world.startAsyncOp("op_b");

            assertTrue(world.hasPendingAsyncOps());

            f1.completeExceptionally(new RuntimeException("NPC1 path blocked"));
            assertTrue(world.hasPendingAsyncOps(), "One failed, one still in-flight");

            f2.complete(null);
            assertFalse(world.hasPendingAsyncOps(), "Both resolved (one success, one failure) → gate open");
        }

        @Test
        void sameFutureCompletedSecondTime_noError() {
            // Idempotent — completing an already-completed future doesn't break the list
            CompletableFuture<Void> f1 = world.startAsyncOp("op_1");
            f1.complete(null);

            assertFalse(world.hasPendingAsyncOps());

            // Second complete is a no-op (future already done)
            f1.complete(null);
            assertFalse(world.hasPendingAsyncOps(), "Double complete should be harmless");
        }

        @Test
        void completesInAnyOrder_gateOpensWhenLastFinishes() {
            CompletableFuture<Void> f1 = world.startAsyncOp("first");
            CompletableFuture<Void> f2 = world.startAsyncOp("second");
            CompletableFuture<Void> f3 = world.startAsyncOp("third");

            // Complete in reverse order
            f3.complete(null);
            f2.complete(null);
            assertTrue(world.hasPendingAsyncOps(), "f1 still pending");

            f1.complete(null);
            assertFalse(world.hasPendingAsyncOps());
        }
    }

    // ===================================================================
    // 3. whenComplete callback correctness
    // ===================================================================

    @Nested
    class WhenCompleteCallbackTests {
        private World world;

        @BeforeEach
        void setUp() {
            world = new World();
        }

        @Test
        void completeTriggersCallback() {
            AtomicBoolean callbackFired = new AtomicBoolean(false);
            CompletableFuture<Void> future = world.startAsyncOp("test");
            future.whenComplete((v, ex) -> callbackFired.set(true));

            future.complete(null);
            assertTrue(callbackFired.get(), "whenComplete should fire on complete");
            assertFalse(world.hasPendingAsyncOps());
        }

        @Test
        void exceptionTriggersCallback() {
            AtomicBoolean callbackFired = new AtomicBoolean(false);
            Throwable[] capturedEx = {null};
            CompletableFuture<Void> future = world.startAsyncOp("test");
            future.whenComplete((v, ex) -> {
                callbackFired.set(true);
                capturedEx[0] = ex;
            });

            RuntimeException cause = new RuntimeException("pathfind timeout");
            future.completeExceptionally(cause);

            assertTrue(callbackFired.get(), "whenComplete should fire on exception");
            assertNotNull(capturedEx[0], "Exception should be passed to callback");
            assertEquals(cause, capturedEx[0]);
            assertFalse(world.hasPendingAsyncOps());
        }
    }

    // ===================================================================
    // 4. Integration: gating interacts correctly with engine tick
    // ===================================================================

    @Nested
    class GatingIntegrationTests {
        private World world;

        @BeforeEach
        void setUp() {
            world = new World();
        }

        @Test
        void gateDoesNotBlockWhenNoOpStarted() {
            // Simulate Wandscape.onServerTick:
            // if (world.hasPendingAsyncOps()) return;
            // world.tick(1.0f);
            for (int i = 0; i < 100; i++) {
                if (world.hasPendingAsyncOps()) {
                    fail("Gate should NOT block when no async ops are in flight");
                }
                // world.tick(1.0f) would run here
            }
        }

        @Test
        void gateBlocksUntilAllResolved_thenAdvances() {
            // Scenario: 2 MoveOps start, engine tick skipped until both complete
            CompletableFuture<Void> move1 = world.startAsyncOp("move_npc1_to_10_64_5");
            CompletableFuture<Void> move2 = world.startAsyncOp("move_npc2_to_20_64_8");

            int skippedTicks = 0;
            int engineTicks = 0;

            // MC tick 1-5: gate blocked
            for (int i = 0; i < 5; i++) {
                if (world.hasPendingAsyncOps()) {
                    skippedTicks++;
                } else {
                    engineTicks++;
                }
            }
            assertEquals(5, skippedTicks);
            assertEquals(0, engineTicks, "Engine should NOT advance while ops in-flight");

            // Both ops resolve
            move1.complete(null);
            move2.complete(null);

            // MC tick 6: gate open → engine advances
            if (!world.hasPendingAsyncOps()) {
                engineTicks++;
            }
            assertEquals(1, engineTicks, "Engine should advance after all ops resolved");
        }

        @Test
        void gateReopensOnlyAfterAllOpsResolve_notAfterFirst() {
            CompletableFuture<Void> f1 = world.startAsyncOp("move_a");
            CompletableFuture<Void> f2 = world.startAsyncOp("move_b");

            // MC tick while f1 is done but f2 is pending
            f1.complete(null);

            int engineTicks = 0;
            for (int i = 0; i < 10; i++) {
                if (!world.hasPendingAsyncOps()) {
                    engineTicks++;
                }
            }
            assertEquals(0, engineTicks,
                    "Gate should stay closed: f1 done but f2 still pending");

            f2.complete(null);
            assertFalse(world.hasPendingAsyncOps(),
                    "Both done → gate open");
        }
    }

    // ===================================================================
    // 5. Edge cases
    // ===================================================================

    @Nested
    class EdgeCaseTests {
        private World world;

        @BeforeEach
        void setUp() {
            world = new World();
        }

        @Test
        void labelCanBeEmpty() {
            CompletableFuture<Void> f = world.startAsyncOp("");
            assertTrue(world.hasPendingAsyncOps());
            f.complete(null);
            assertFalse(world.hasPendingAsyncOps());
        }

        @Test
        void rapidStartComplete_doesNotCorruptState() {
            for (int i = 0; i < 100; i++) {
                CompletableFuture<Void> f = world.startAsyncOp("rapid_" + i);
                f.complete(null);
            }
            assertFalse(world.hasPendingAsyncOps(),
                    "After 100 rapid start-complete cycles, state should be clean");
        }

        @Test
        void startAsyncOpAfterComplete_works() {
            // Complete → gate open → start new → gate closes → complete
            CompletableFuture<Void> f1 = world.startAsyncOp("batch1_op");
            f1.complete(null);
            assertFalse(world.hasPendingAsyncOps());

            CompletableFuture<Void> f2 = world.startAsyncOp("batch2_op");
            assertTrue(world.hasPendingAsyncOps());
            f2.complete(null);
            assertFalse(world.hasPendingAsyncOps());
        }
    }
}
