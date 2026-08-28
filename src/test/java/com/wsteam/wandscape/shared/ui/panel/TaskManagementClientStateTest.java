package com.wsteam.wandscape.shared.ui.panel;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.shared.network.tasks.MageSummaryDto;
import com.wsteam.wandscape.shared.network.tasks.ResourceShortageDto;
import com.wsteam.wandscape.shared.network.tasks.TaskManagementSyncPacket;
import com.wsteam.wandscape.shared.network.tasks.TaskSummaryDto;

import com.wsteam.wandscape.shared.network.tasks.ProductionGroupDto;
import com.wsteam.wandscape.shared.network.tasks.ProductionItemDto;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagementClientStateTest {

    @BeforeEach
    void setUp() {
        TaskManagementClientState.reset();
    }

    @Test
    void testSyncAndFiltering() {
        UUID colonyId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();

        TaskSummaryDto t1 = new TaskSummaryDto(
                1L, "build", "建造 铁匠铺", "bp:build", buildingId, "铁匠铺",
                "IN_PROGRESS", 50, 2, 5, -1, 0,
                10L, UUID.randomUUID(), "埃尔德里奇",
                List.of(), true, 100, 64, 200, "IN_PROGRESS"
        );

        TaskSummaryDto t2 = new TaskSummaryDto(
                2L, "craft", "合成 治疗药水", "bp:craft", null, "",
                "AWAITING_RESOURCES", 30, 0, 1, -1, 0,
                -1L, null, "",
                List.of(new ResourceShortageDto("element", "water", "水元素", 50, 10)),
                false, 0, 0, 0, "MISSING_RESOURCES"
        );

        TaskSummaryDto t3 = new TaskSummaryDto(
                3L, "guard", "守卫 农田", "bp:guard", null, "",
                "PENDING_ASSIGN", 80, 0, 1, -1, 0,
                -1L, null, "",
                List.of(), false, 0, 0, 0, "WAITING_NPC"
        );

        MageSummaryDto m1 = new MageSummaryDto(
                10L, UUID.randomUUID(), 1001, "埃尔德里奇", "CASTING",
                20f, 20f, 80f, 100f, 1.5f, 1.2f, 1.0f, 4f,
                "建造 铁匠铺", 1L, "精致法杖",
                100.0, 64.0, 200.0, false, false
        );

        MageSummaryDto m2 = new MageSummaryDto(
                11L, UUID.randomUUID(), 1002, "梅林", "IDLE",
                18f, 20f, 100f, 100f, 2.0f, 1.0f, 1.2f, 2f,
                "", -1L, "",
                105.0, 64.0, 205.0, true, true
        );

        ProductionItemDto pItem1 = new ProductionItemDto(
                100L, 0, "synthesize", "production:synthesize", "minecraft:campfire", "营火", 14,
                "RUNNING", 10L, "埃尔德里奇", 0.45f,
                List.of(), List.of(), "arrow_store 建造缺料自动派发", false
        );

        ProductionItemDto pItem2 = new ProductionItemDto(
                -5001L, 1, "synthesize", "production:synthesize", "minecraft:lantern", "灯笼", 1,
                "MISSING_ELEMENTS", -1L, "", 0.0f,
                List.of(new ResourceShortageDto("element", "fire", "火元素", 28, 0)),
                List.of("fire"), "arrow_store 建造缺料自动派发", true
        );

        ProductionGroupDto group1 = new ProductionGroupDto(
                buildingId, "初级工作站", "workstation",
                100, 64, 200, 1, List.of(pItem1, pItem2)
        );

        TaskManagementSyncPacket packet = new TaskManagementSyncPacket(
                colonyId, List.of(t1, t2, t3), List.of(group1), List.of(m1, m2), 3, 1, 2
        );

        TaskManagementClientState.update(packet);

        assertEquals(3, TaskManagementClientState.getTotalActiveTasks());
        assertEquals(1, TaskManagementClientState.getIdleMageCount());
        assertEquals(2, TaskManagementClientState.getTotalMageCount());
        assertEquals(2, TaskManagementClientState.getTotalProductionItemCount());

        // Default Filter: ALL
        assertEquals(3, TaskManagementClientState.getFilteredTasks().size());

        // Filter: IN_PROGRESS
        TaskManagementClientState.setActiveFilter(TaskManagementClientState.TaskFilter.IN_PROGRESS);
        assertEquals(1, TaskManagementClientState.getFilteredTasks().size());
        assertEquals(1L, TaskManagementClientState.getFilteredTasks().get(0).taskId());

        // Filter: AWAITING_RESOURCES
        TaskManagementClientState.setActiveFilter(TaskManagementClientState.TaskFilter.AWAITING_RESOURCES);
        assertEquals(1, TaskManagementClientState.getFilteredTasks().size());
        assertEquals(2L, TaskManagementClientState.getFilteredTasks().get(0).taskId());
        assertEquals(40, TaskManagementClientState.getFilteredTasks().get(0).shortages().get(0).getMissingAmount());

        // Search Query
        TaskManagementClientState.setActiveFilter(TaskManagementClientState.TaskFilter.ALL);
        TaskManagementClientState.setSearchQuery("药水");
        assertEquals(1, TaskManagementClientState.getFilteredTasks().size());
        assertEquals("合成 治疗药水", TaskManagementClientState.getFilteredTasks().get(0).title());

        // Production Tab & Filters
        TaskManagementClientState.setSearchQuery("");
        TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.PRODUCTION);
        assertEquals(TaskManagementClientState.SubTab.PRODUCTION, TaskManagementClientState.getActiveTab());
        assertEquals(1, TaskManagementClientState.getFilteredProductionGroups().size());
        assertEquals(2, TaskManagementClientState.getFilteredProductionGroups().get(0).items().size());

        // Filter Production: RUNNING
        TaskManagementClientState.setActiveProductionFilter(TaskManagementClientState.ProductionFilter.RUNNING);
        List<ProductionGroupDto> runningGroups = TaskManagementClientState.getFilteredProductionGroups();
        assertEquals(1, runningGroups.size());
        assertEquals(1, runningGroups.get(0).items().size());
        assertEquals("营火", runningGroups.get(0).items().get(0).displayName());

        // Filter Production: MISSING_ELEMENTS
        TaskManagementClientState.setActiveProductionFilter(TaskManagementClientState.ProductionFilter.MISSING_ELEMENTS);
        List<ProductionGroupDto> missingGroups = TaskManagementClientState.getFilteredProductionGroups();
        assertEquals(1, missingGroups.size());
        assertEquals(1, missingGroups.get(0).items().size());
        assertEquals("灯笼", missingGroups.get(0).items().get(0).displayName());
        assertTrue(missingGroups.get(0).items().get(0).activeSupplyingGather());

        // Select Production Virtual ID for modal
        TaskManagementClientState.setSelectedProductionVirtualId(-5001L);
        assertEquals(-5001L, TaskManagementClientState.getSelectedProductionVirtualId());

        // Mage List
        TaskManagementClientState.setActiveTab(TaskManagementClientState.SubTab.MAGES);
        assertEquals(2, TaskManagementClientState.getFilteredMages().size());
        TaskManagementClientState.setSearchQuery("梅林");
        assertEquals(1, TaskManagementClientState.getFilteredMages().size());
        assertTrue(TaskManagementClientState.getFilteredMages().get(0).followMode());
    }
}
