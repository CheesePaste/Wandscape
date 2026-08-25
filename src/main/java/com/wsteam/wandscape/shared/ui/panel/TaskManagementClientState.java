package com.wsteam.wandscape.shared.ui.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.network.tasks.MageSummaryDto;
import com.wsteam.wandscape.shared.network.tasks.TaskManagementSyncPacket;
import com.wsteam.wandscape.shared.network.tasks.TaskSummaryDto;

/**
 * Client-side state container for the Task & Mage Management Drawer.
 */
public final class TaskManagementClientState {

    public enum SubTab {
        TASKS,
        MAGES
    }

    public enum TaskFilter {
        ALL("gui.wandscape.panel.tasks.filter.all"),
        IN_PROGRESS("gui.wandscape.panel.tasks.filter.in_progress"),
        AWAITING_RESOURCES("gui.wandscape.panel.tasks.filter.awaiting"),
        PENDING("gui.wandscape.panel.tasks.filter.pending"),
        QUEUED("gui.wandscape.panel.tasks.filter.queued");

        private final String translationKey;

        TaskFilter(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getTranslationKey() {
            return translationKey;
        }
    }

    private static volatile SubTab activeTab = SubTab.TASKS;
    private static volatile TaskFilter activeFilter = TaskFilter.ALL;
    private static volatile String searchQuery = "";
    private static volatile int taskScrollOffset = 0;
    private static volatile int mageScrollOffset = 0;

    private static volatile UUID colonyId = null;
    private static volatile List<TaskSummaryDto> allTasks = List.of();
    private static volatile List<MageSummaryDto> allMages = List.of();
    private static volatile int totalActiveTasks = 0;
    private static volatile int idleMageCount = 0;
    private static volatile int totalMageCount = 0;

    private static volatile long selectedTaskId = -1;
    private static volatile long selectedMageEcsId = -1;
    private static volatile int trackingEntityId = -1;

    private TaskManagementClientState() {}

    public static void update(TaskManagementSyncPacket packet) {
        colonyId = packet.colonyId();
        allTasks = packet.tasks() != null ? packet.tasks() : List.of();
        allMages = packet.mages() != null ? packet.mages() : List.of();
        totalActiveTasks = packet.totalActiveTasks();
        idleMageCount = packet.idleMageCount();
        totalMageCount = packet.totalMageCount();
    }

    public static void reset() {
        activeTab = SubTab.TASKS;
        activeFilter = TaskFilter.ALL;
        searchQuery = "";
        taskScrollOffset = 0;
        mageScrollOffset = 0;
        colonyId = null;
        allTasks = List.of();
        allMages = List.of();
        totalActiveTasks = 0;
        idleMageCount = 0;
        totalMageCount = 0;
        selectedTaskId = -1;
        selectedMageEcsId = -1;
        trackingEntityId = -1;
    }

    public static SubTab getActiveTab() { return activeTab; }
    public static void setActiveTab(SubTab tab) {
        activeTab = tab;
    }

    public static TaskFilter getActiveFilter() { return activeFilter; }
    public static void setActiveFilter(TaskFilter filter) {
        activeFilter = filter;
        taskScrollOffset = 0;
    }

    public static String getSearchQuery() { return searchQuery; }
    public static void setSearchQuery(String query) {
        searchQuery = query != null ? query : "";
    }

    public static int getTaskScrollOffset() { return taskScrollOffset; }
    public static void setTaskScrollOffset(int offset) { taskScrollOffset = Math.max(0, offset); }

    public static int getMageScrollOffset() { return mageScrollOffset; }
    public static void setMageScrollOffset(int offset) { mageScrollOffset = Math.max(0, offset); }

    public static List<TaskSummaryDto> getAllTasks() { return allTasks; }
    public static List<MageSummaryDto> getAllMages() { return allMages; }

    public static int getTotalActiveTasks() { return totalActiveTasks; }
    public static int getIdleMageCount() { return idleMageCount; }
    public static int getTotalMageCount() { return totalMageCount; }

    public static long getSelectedTaskId() { return selectedTaskId; }
    public static void setSelectedTaskId(long id) { selectedTaskId = id; }

    public static long getSelectedMageEcsId() { return selectedMageEcsId; }
    public static void setSelectedMageEcsId(long id) { selectedMageEcsId = id; }

    public static int getTrackingEntityId() { return trackingEntityId; }
    public static void setTrackingEntityId(int entityId) { trackingEntityId = entityId; }

    public static List<TaskSummaryDto> getFilteredTasks() {
        List<TaskSummaryDto> list = new ArrayList<>();
        String query = searchQuery.trim().toLowerCase();

        for (TaskSummaryDto t : allTasks) {
            // State filter
            boolean matchFilter = switch (activeFilter) {
                case ALL -> true;
                case IN_PROGRESS -> "IN_PROGRESS".equalsIgnoreCase(t.state());
                case AWAITING_RESOURCES -> "AWAITING_RESOURCES".equalsIgnoreCase(t.state());
                case PENDING -> "PENDING_ASSIGN".equalsIgnoreCase(t.state());
                case QUEUED -> "QUEUED".equalsIgnoreCase(t.state());
            };
            if (!matchFilter) continue;

            // Search query
            if (!query.isEmpty()) {
                boolean matchSearch = (t.title() != null && t.title().toLowerCase().contains(query))
                        || (t.buildingName() != null && t.buildingName().toLowerCase().contains(query))
                        || (t.assignedNpcName() != null && t.assignedNpcName().toLowerCase().contains(query));
                if (!matchSearch) continue;
            }

            list.add(t);
        }
        return list;
    }

    public static List<MageSummaryDto> getFilteredMages() {
        List<MageSummaryDto> list = new ArrayList<>();
        String query = searchQuery.trim().toLowerCase();

        for (MageSummaryDto m : allMages) {
            if (!query.isEmpty()) {
                boolean matchSearch = (m.name() != null && m.name().toLowerCase().contains(query))
                        || (m.currentTaskTitle() != null && m.currentTaskTitle().toLowerCase().contains(query))
                        || (m.state() != null && m.state().toLowerCase().contains(query));
                if (!matchSearch) continue;
            }
            list.add(m);
        }
        return list;
    }
}
