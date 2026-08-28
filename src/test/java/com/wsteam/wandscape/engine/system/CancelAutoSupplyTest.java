package com.wsteam.wandscape.engine.system;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelAutoSupplyTest {

    private World world;
    private UUID colonyId;
    private UUID stationId;
    private List<WorkItem> stationQueue;

    @BeforeEach
    void setUp() {
        MockBoundary mock = new MockBoundary();
        BlueprintRegistry blueprints = new BlueprintRegistry();
        blueprints.register("production:synthesize", params -> new TaskSequence(List.of(), "synthesize"));

        MovementOps noopMov = new MovementOps() {
            @Override
            public java.util.concurrent.CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelNavigation(long npcId) {}
        };

        CoreBootstrapConfig config = new CoreBootstrapConfig(
                mock, mock, mock, noopMov, mock, List.of(), blueprints,
                new SystemBlueprintRegistry(), false);
        world = CoreBootstrap.bootstrap(config);

        colonyId = UUID.randomUUID();
        stationId = UUID.randomUUID();
        stationQueue = new ArrayList<>();

        BuildingApi mockBuildingApi = new MockBuildingApi(colonyId, stationId, stationQueue);
        WandscapeApis.setBuildingApi(mockBuildingApi);
    }

    @AfterEach
    void tearDown() {
        WandscapeApis.setBuildingApi(null);
    }

    @Test
    void cancelAutoSynthesize_removesMatchingWorkItemFromQueue() {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("recipe_id", new JsonPrimitive("minecraft:chest"));
        params.put("count", new JsonPrimitive(2));
        WorkItem autoItem = new WorkItem("production:synthesize", params, WandscapeConstants.TASK_PRIORITY_AUTO);
        stationQueue.add(autoItem);

        // Cancel chests for cancelled building
        ResourceSupplySystem.cancelAutoSynthesize(colonyId, Map.of("minecraft:chest", 2), world);

        assertTrue(stationQueue.isEmpty(), "Auto-synthesize work item should be removed from workstation queue");
    }

    @Test
    void cancelAutoSynthesize_partiallyReducesCountWhenNeeded() {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("recipe_id", new JsonPrimitive("minecraft:chest"));
        params.put("count", new JsonPrimitive(5));
        WorkItem autoItem = new WorkItem("production:synthesize", params, WandscapeConstants.TASK_PRIORITY_AUTO);
        stationQueue.add(autoItem);

        // Cancel 2 chests
        ResourceSupplySystem.cancelAutoSynthesize(colonyId, Map.of("minecraft:chest", 2), world);

        assertEquals(1, stationQueue.size());
        assertEquals(3, stationQueue.get(0).params().get("count").getAsInt());
    }

    @Test
    void cancelAutoSynthesize_doesNotTouchHigherPriorityPlayerOrRestockTasks() {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("recipe_id", new JsonPrimitive("minecraft:chest"));
        params.put("count", new JsonPrimitive(2));
        WorkItem restockItem = new WorkItem("production:synthesize", params, WandscapeConstants.TASK_PRIORITY_RESTOCK);
        stationQueue.add(restockItem);

        // Try to cancel chests
        ResourceSupplySystem.cancelAutoSynthesize(colonyId, Map.of("minecraft:chest", 2), world);

        assertEquals(1, stationQueue.size(), "Shop restock task must not be cancelled");
    }

    @Test
    void cancelAutoSynthesize_cancelsRunningPoolTaskIfQueuedItemNotEnough() {
        Map<String, JsonElement> taskParams = new HashMap<>();
        taskParams.put("recipe_id", new JsonPrimitive("minecraft:campfire"));
        taskParams.put("count", new JsonPrimitive(4));
        long taskId = world.taskPool.addTask(new TaskRequest(
                "production:synthesize", taskParams, WandscapeConstants.TASK_PRIORITY_AUTO, colonyId));
        GlobalTask task = world.taskPool.get(taskId);
        task.buildingId = stationId;

        ResourceSupplySystem.cancelAutoSynthesize(colonyId, Map.of("minecraft:campfire", 4), world);

        assertEquals(TaskState.COMPLETED, task.state, "Running global task should be cancelled");
    }

    private static class MockBuildingData implements BuildingData {
        private final UUID buildingId;
        private final UUID colonyId;

        MockBuildingData(UUID buildingId, UUID colonyId) {
            this.buildingId = buildingId;
            this.colonyId = colonyId;
        }

        @Override public UUID getBuildingId() { return buildingId; }
        @Override public UUID getColonyId() { return colonyId; }
        @Override public String getBuildingTypeId() { return "workstation1"; }
        @Override public String getCategory() { return "workstation"; }
        @Override public BlockPos getPosition() { return BlockPos.ZERO; }
        @Override public int getComfort() { return 0; }
        @Override public int getMagic() { return 0; }
        @Override public int getWonder() { return 0; }
        @Override public boolean isStructureIntact() { return true; }
    }

    private static class MockBuildingApi implements BuildingApi {
        private final UUID colonyId;
        private final UUID stationId;
        private final List<WorkItem> queue;

        MockBuildingApi(UUID colonyId, UUID stationId, List<WorkItem> queue) {
            this.colonyId = colonyId;
            this.stationId = stationId;
            this.queue = queue;
        }

        @Override
        public List<UUID> getBuildingsByCategory(UUID colonyId, String category) {
            if ("workstation".equals(category)) {
                return List.of(stationId);
            }
            return List.of();
        }

        @Override
        public BuildingData getBuilding(UUID buildingId) {
            if (stationId.equals(buildingId)) {
                return new MockBuildingData(stationId, colonyId);
            }
            return null;
        }

        @Override
        public List<WorkItem> getQueue(UUID buildingId) {
            return new ArrayList<>(queue);
        }

        @Override
        public boolean removeFromQueue(UUID buildingId, int index) {
            if (index >= 0 && index < queue.size()) {
                queue.remove(index);
                return true;
            }
            return false;
        }

        @Override
        public void enqueueWork(UUID buildingId, WorkItem work) {
            queue.add(work);
        }

        @Override public BuildingData getBuildingAt(BlockPos pos) { return null; }
        @Override public List<BuildingData> getColonyBuildings(UUID colonyId) { return List.of(); }
        @Override public BoundingBox getBuildingBounds(UUID buildingId) { return null; }
        @Override public void registerBuilding(BuildingData data) {}
        @Override public void unregisterBuilding(BlockPos pos) {}
        @Override public void demolishBuilding(UUID buildingId) {}
        @Override public boolean isDemolishing(UUID buildingId) { return false; }
        @Override public Component demolishBlockReason(UUID buildingId) { return null; }
        @Override public boolean cancelBuilding(UUID buildingId) { return false; }
        @Override public ColonySnapshot getColonySnapshot(UUID colonyId) { return null; }
        @Override public int getColonyComfort(UUID colonyId) { return 0; }
        @Override public int getColonyMagic(UUID colonyId) { return 0; }
        @Override public int getColonyWonder(UUID colonyId) { return 0; }
        @Override public boolean isBuildingOccupied(UUID buildingId) { return false; }
        @Override public List<UUID> getBuildingsWithPendingWork(UUID colonyId) { return List.of(); }
        @Override public WorkItem dequeueWork(UUID buildingId) { return null; }
        @Override public WorkItem dequeueWorkEligible(UUID buildingId, java.util.function.Predicate<WorkItem> eligible) { return null; }
        @Override public void setCurrentTask(UUID buildingId, UUID taskId) {}
        @Override public boolean moveUp(UUID buildingId, int index) { return false; }
        @Override public boolean moveDown(UUID buildingId, int index) { return false; }
        @Override public void clearCurrentTask(UUID buildingId) {}
        @Override public PlacementResult placeBuilding(BlockPos anchor, String buildingTypeId, int rotationSteps) { return null; }
        @Override public boolean isFirstFreeClaimed(UUID colonyId, String buildingTypeId) { return false; }
        @Override public List<BlockPos> findBeds(UUID buildingId) { return List.of(); }
        @Override public List<BlockPos> sampleWalkableGround(UUID buildingId, int count) { return List.of(); }
        @Override public BlockPos getTouristInteractionTarget(UUID buildingId) { return null; }
        @Override public BlockPos getEntryPoint(UUID buildingId) { return null; }
        @Override public BlockPos getTouristInteractPoint(UUID buildingId) { return null; }
    }
}
