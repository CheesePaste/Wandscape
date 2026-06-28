package com.wsteam.wandscape.blueprint.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.blueprint.editor.BlueprintEditorCanvas.CanvasNode;
import com.wsteam.wandscape.core.task.ParamType;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.extension.nodeditor.NodeEditor;
import imgui.extension.nodeditor.NodeEditorContext;
import imgui.extension.nodeditor.flag.NodeEditorPinKind;
import imgui.extension.nodeditor.flag.NodeEditorStyleColor;
import imgui.type.ImLong;
import imgui.type.ImString;
import imgui.type.ImInt;

import com.wsteam.wandscape.shared.log.Log;

/**
 * ImGui renderer for the blueprint node editor.
 *
 * <p>Renders three zones:
 * <ol>
 *   <li><b>Canvas</b> (center) — node graph with exec + data edges</li>
 *   <li><b>Inspector</b> (right panel, 250px) — context-sensitive property editor</li>
 *   <li><b>Search palette</b> (floating popup) — node type search on right-click</li>
 * </ol>
 *
 * <p>Pin ID encoding: {@code (nodeId << 8) | pinIndex} — up to 256 pins per node.
 */
public final class BlueprintEditorImGui {

    private static final String TAG = "BlueprintEditorImGui";
    private static final float INSPECTOR_WIDTH = 260f;
    private static final float TOP_BAR_HEIGHT = 48f;

    // ── ImGui widget wrappers (reused across frames) ──
    private static final ImString idBuf = new ImString(128);
    private static final ImString nameBuf = new ImString(256);
    private static final ImString descBuf = new ImString(1024);
    private static final ImString searchBuf = new ImString(64);
    private static final ImString paramNameBuf = new ImString(64);
    private static final ImInt paramTypeIdx = new ImInt(0);
    private static final ImString inlineValBuf = new ImString(256);

    // ── Load popup state ──
    private static boolean loadPopupOpen = false;
    private static final ImString loadJsonBuf = new ImString(8192);

    private static final String[] PARAM_TYPES = {
            "string", "int", "pos", "list<pos>", "list<string>", "map<string,string>"
    };

    private static final String[] LOG_LEVELS = { "info", "warn", "debug" };
    private static final String[] INTERACT_ACTIONS = { "toggle", "activate", "open_gui", "gather", "decompose", "synthesize" };
    private static final String[] FIELD_OPTIONS = { "x", "y", "z" };

    private BlueprintEditorImGui() {}

    // ═══════════════════════════════════════════════════════════════
    // Pin ID encoding
    // ═══════════════════════════════════════════════════════════════

    private static long pinId(long nodeId, int pinIndex) {
        return (nodeId << 8) | (pinIndex & 0xFF);
    }

    private static long nodeIdFromPin(long pinId) {
        return pinId >> 8;
    }

    private static int pinIndexFromPin(long pinId) {
        return (int) (pinId & 0xFF);
    }

    // ═══════════════════════════════════════════════════════════════
    // Main render entry
    // ═══════════════════════════════════════════════════════════════

    public static void render(NodeEditorContext ctx) {
    System.out.println("render.0 isEditing=" + BlueprintEditorClientState.isEditing());

    if (!BlueprintEditorClientState.isEditing()) return;

    System.out.println("render.1 getCanvas");
    BlueprintEditorCanvas graph = BlueprintEditorClientState.getCanvas();
    if (graph == null) return;
    System.out.println("render.2 nodes=" + graph.nodes.size());

    var io = ImGui.getIO();
    System.out.println("render.3 displaySize=" + io.getDisplaySizeX() + "x" + io.getDisplaySizeY());

    float winW = io.getDisplaySizeX() - INSPECTOR_WIDTH;
    float winH = io.getDisplaySizeY();
    System.out.println("render.4 winSize=" + winW + "x" + winH);

    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(winW, winH, ImGuiCond.Always);
    System.out.println("render.5 setNextWindow done");

    int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoBringToFrontOnFocus;

    System.out.println("render.6 before begin");
    if (ImGui.begin("Blueprint Canvas", flags)) {
        System.out.println("render.7 begin ok");

        // 在这里进一步细分
        renderTopBar(graph);
        System.out.println("render.8 topBar done");

        renderNodeCanvas(graph, ctx);
        System.out.println("render.9 nodeCanvas done");
    }
    System.out.println("render.10 before end");
    ImGui.end();
    System.out.println("render.11 end done");

    if (BlueprintEditorClientState.isInspectorVisible()) {
        System.out.println("render.12 before inspector");
        renderInspector(graph);
        System.out.println("render.13 inspector done");
    }

    if (BlueprintEditorClientState.isSearchPaletteOpen()) {
        System.out.println("render.14 before search");
        renderSearchPalette(graph);
        System.out.println("render.15 search done");
    }

    if (loadPopupOpen) {
        System.out.println("render.16 before loadPopup");
        renderLoadPopup(graph);
        System.out.println("render.17 loadPopup done");
    }

    System.out.println("render.18 ALL DONE");
}

    // ═══════════════════════════════════════════════════════════════
    // Top bar
    // ═══════════════════════════════════════════════════════════════

    private static void renderTopBar(BlueprintEditorCanvas graph) {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4, 4);
        ImGui.pushItemWidth(200);
        ImGui.text("ID:");
        ImGui.sameLine();
        idBuf.set(graph.blueprintId);
        if (ImGui.inputText("##bpId", idBuf)) {
            graph.blueprintId = idBuf.get();
            BlueprintEditorClientState.markDirty();
        }
        ImGui.sameLine();
        ImGui.text("Name:");
        ImGui.sameLine();
        nameBuf.set(graph.displayName);
        if (ImGui.inputText("##bpName", nameBuf)) {
            graph.displayName = nameBuf.get();
            BlueprintEditorClientState.markDirty();
        }
        ImGui.sameLine();
        ImGui.text("Desc:");
        ImGui.sameLine();
        descBuf.set(graph.description);
        if (ImGui.inputText("##bpDesc", descBuf)) {
            graph.description = descBuf.get();
            BlueprintEditorClientState.markDirty();
        }
        ImGui.popItemWidth();

        // Action buttons
        ImGui.sameLine();
        if (ImGui.button("New")) {
            BlueprintEditorClientState.setCanvas(new BlueprintEditorCanvas());
        }
        ImGui.sameLine();
        if (ImGui.button("Save")) {
            BlueprintEditorController.doSave();
        }
        ImGui.sameLine();
        if (ImGui.button("Load")) {
            loadPopupOpen = true;
            loadJsonBuf.set("");
        }
        ImGui.sameLine();
        if (ImGui.button("Inspector")) {
            BlueprintEditorClientState.toggleInspector();
        }
        ImGui.sameLine();
        if (ImGui.button("Close")) {
            BlueprintEditorController.doExit();
        }

        ImGui.popStyleVar();
        ImGui.separator();
    }

    // ═══════════════════════════════════════════════════════════════
    // Node canvas
    // ═══════════════════════════════════════════════════════════════

    private static void renderNodeCanvas(BlueprintEditorCanvas graph, NodeEditorContext ctx) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0xFF1E1E2E);

        NodeEditor.setCurrentEditor(ctx);
        NodeEditor.begin("BlueprintNodeCanvas");

        // ── Render nodes ──
        List<CanvasNode> sortedNodes = new ArrayList<>(graph.nodes.values());
        // Render step nodes first (larger), then expression nodes, then input nodes
        sortedNodes.sort((a, b) -> {
            int catA = nodeCategoryOrder(a);
            int catB = nodeCategoryOrder(b);
            return Integer.compare(catA, catB);
        });

        for (CanvasNode node : sortedNodes) {
            renderNode(node, graph);
        }

        // ── Handle link creation ──
        if (NodeEditor.beginCreate()) {
            final ImLong a = new ImLong();
            final ImLong b = new ImLong();
            if (NodeEditor.queryNewLink(a, b)) {
                long fromPin = a.get();
                long toPin = b.get();
                long fromNodeId = nodeIdFromPin(fromPin);
                long toNodeId = nodeIdFromPin(toPin);
                int fromPinIdx = pinIndexFromPin(fromPin);
                int toPinIdx = pinIndexFromPin(toPin);

                if (NodeEditor.acceptNewItem()) {
                    CanvasNode fromNode = graph.nodes.get(fromNodeId);
                    CanvasNode toNode = graph.nodes.get(toNodeId);
                    if (fromNode != null && toNode != null && fromNodeId != toNodeId) {
                        createLink(fromNode, toNode, fromPinIdx, toPinIdx, graph);
                    }
                }
            }
        }
        NodeEditor.endCreate();

        // ── Draw links ──
        int uniqueLinkId = 1;
        for (BlueprintEditorCanvas.ExecEdge ee : graph.execEdges) {
            CanvasNode from = graph.nodes.get(ee.fromNodeId());
            CanvasNode to = graph.nodes.get(ee.toNodeId());
            if (from != null && to != null) {
                int fromIdx = pinIndexOf(from, ee.fromPinId());
                int toIdx = pinIndexOf(to, ee.toPinId());
                if (fromIdx >= 0 && toIdx >= 0) {
                    NodeEditor.link(uniqueLinkId++, pinId(from.nodeId, fromIdx),
                            pinId(to.nodeId, toIdx));
                }
            }
        }

        for (BlueprintEditorCanvas.DataEdge de : graph.dataEdges) {
            CanvasNode from = graph.nodes.get(de.fromNodeId());
            CanvasNode to = graph.nodes.get(de.toNodeId());
            if (from != null && to != null) {
                int fromIdx = pinIndexOf(from, de.fromPinId());
                int toIdx = pinIndexOf(to, de.toPinId());
                if (fromIdx >= 0 && toIdx >= 0) {
                    // Color data links by type using NodeEditor pushStyleColor
                    String typeKey = pinTypeKey(to, de.toPinId());
                    float[] rgba = pinColorRGBA(typeKey);
                    NodeEditor.pushStyleColor(NodeEditorStyleColor.Flow,
                            rgba[0], rgba[1], rgba[2], rgba[3]);
                    NodeEditor.link(uniqueLinkId++, pinId(from.nodeId, fromIdx),
                            pinId(to.nodeId, toIdx));
                    NodeEditor.popStyleColor(1);
                }
            }
        }

        // ── Context menus ──
        NodeEditor.suspend();

        // Node right-click context menu
        final long ctxNodeId = NodeEditor.getNodeWithContextMenu();
        if (ctxNodeId != -1) {
            ImGui.openPopup("node_context");
            ImGui.getStateStorage().setInt(ImGui.getID("ctx_node_id"), (int) ctxNodeId);
        }
        renderNodeContextMenu(graph);

        // Background context menu → search palette
        if (NodeEditor.showBackgroundContextMenu()) {
            BlueprintEditorClientState.setSearchPaletteOpen(true);
            BlueprintEditorClientState.setSearchQuery("");
            searchBuf.set("");
        }

        NodeEditor.resume();
        NodeEditor.end();
        ImGui.popStyleColor();

        // Selection: sync native node-editor selection state (standard click-to-select)
        // MUST be called after End() — native backend finalizes click events there
        long[] selectedNodes = new long[1];
        if (NodeEditor.getSelectedNodes(selectedNodes, 1) > 0) {
            // A node is selected in the native editor — sync to our ClientState
            BlueprintEditorClientState.setSelectedNodeId(selectedNodes[0]);
        } else {
            // Background clicked? Clear native selection too
            if (NodeEditor.isBackgroundClicked()) {
                NodeEditor.clearSelection();
            }
            BlueprintEditorClientState.clearSelection();
        }

        // Handle keyboard shortcuts (outside suspend/resume)
        handleShortcuts(graph);
    }

    // ═══════════════════════════════════════════════════════════════
    // Node rendering
    // ═══════════════════════════════════════════════════════════════

    private static void renderNode(CanvasNode node, BlueprintEditorCanvas graph) {
        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        if (def == null) {
            // Unknown type — render a generic fallback
            NodeEditor.beginNode(node.nodeId);
            ImGui.text(node.typeId);
            NodeEditor.beginPin(pinId(node.nodeId, 0), NodeEditorPinKind.Input);
            ImGui.text("In");
            NodeEditor.endPin();
            ImGui.sameLine();
            NodeEditor.beginPin(pinId(node.nodeId, 1), NodeEditorPinKind.Output);
            ImGui.text("Out");
            NodeEditor.endPin();
            NodeEditor.endNode();
            return;
        }

        // Push node style
        ImGui.pushStyleColor(ImGuiCol.Header, def.color());

        NodeEditor.beginNode(node.nodeId);

        boolean isBegin = BlueprintNodeDefinition.CATEGORY_ENTRY.equals(def.category());
        boolean isExpr = BlueprintNodeDefinition.CATEGORY_EXPR.equals(def.category());
        boolean isInput = BlueprintNodeDefinition.CATEGORY_INPUT.equals(def.category());
        boolean isStep = BlueprintNodeDefinition.CATEGORY_STEP.equals(def.category());

        if (isBegin) {
            renderBeginNode(node, def);
        } else if (isStep) {
            renderStepNode(node, def, graph);
        } else if (isExpr) {
            renderExprNode(node, def, graph);
        } else if (isInput) {
            renderInputNode(node, def);
        }

        NodeEditor.endNode();
        ImGui.popStyleColor();
    }

    private static void renderStepNode(CanvasNode node, BlueprintNodeDefinition.NodeDef def,
                                        BlueprintEditorCanvas graph) {
        // Header
        ImGui.textColored(def.color() | 0xFF000000, def.displayName());

        // Render input pins (left side) — exec first, then data
        List<BlueprintNodeDefinition.PinDef> inputPins = getInputPins(def, node);
        for (BlueprintNodeDefinition.PinDef pin : inputPins) {
            NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())),
                    mapPinKind(pin));
            int pinCol = pinColorForType(pin.typeKey());
            ImGui.pushStyleColor(ImGuiCol.Text, pinCol);
            ImGui.text(pin.label().isEmpty() ? pin.id() : pin.label());
            ImGui.popStyleColor();
            NodeEditor.endPin();
        }

        // Render output pins (right side) — exec first, then data
        List<BlueprintNodeDefinition.PinDef> outputPins = getOutputPins(def, node);
        for (BlueprintNodeDefinition.PinDef pin : outputPins) {
            if (inputPins.isEmpty()) {
                // No inputs on this line — put output on same line
            } else {
                ImGui.sameLine();
                // Push to the right
                ImGui.setCursorPosX(ImGui.getCursorPosX() + 80);
            }
            NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())),
                    mapPinKind(pin));
            int pinCol = pinColorForType(pin.typeKey());
            ImGui.pushStyleColor(ImGuiCol.Text, pinCol);
            ImGui.text(pin.label().isEmpty() ? pin.id() : pin.label());
            ImGui.popStyleColor();
            NodeEditor.endPin();
        }

        // Inline preview: show key inline values
        if (node.inlineValues.containsKey("var_name")) {
            ImGui.textDisabled("var: " + node.inlineValues.get("var_name"));
        }
        if (node.inlineValues.containsKey("action")) {
            ImGui.textDisabled("action: " + node.inlineValues.get("action"));
        }
    }

    private static void renderExprNode(CanvasNode node, BlueprintNodeDefinition.NodeDef def,
                                        BlueprintEditorCanvas graph) {
        // Compact rendering for expression nodes
        // Inputs on left, outputs on right, display name in center

        List<BlueprintNodeDefinition.PinDef> inputPins = getInputPins(def, node);
        List<BlueprintNodeDefinition.PinDef> outputPins = getOutputPins(def, node);

        if (!inputPins.isEmpty()) {
            for (BlueprintNodeDefinition.PinDef pin : inputPins) {
                NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())),
                        mapPinKind(pin));
                int pinCol = pinColorForType(pin.typeKey());
                ImGui.pushStyleColor(ImGuiCol.Text, pinCol);
                ImGui.text(pin.label().isEmpty() ? " " : pin.label());
                ImGui.popStyleColor();
                NodeEditor.endPin();
            }
        }

        // Display name + inline value
        String display = def.displayName();
        if (node.inlineValues.containsKey("value")) {
            display += ": " + truncate(node.inlineValues.get("value"), 16);
        } else if (node.inlineValues.containsKey("name")) {
            display = "$" + node.inlineValues.get("name");
        } else if (node.inlineValues.containsKey("field")) {
            display = "." + node.inlineValues.get("field");
        }
        ImGui.text(display);

        if (!outputPins.isEmpty()) {
            ImGui.sameLine();
            for (BlueprintNodeDefinition.PinDef pin : outputPins) {
                NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())),
                        mapPinKind(pin));
                int pinCol = pinColorForType(pin.typeKey());
                ImGui.pushStyleColor(ImGuiCol.Text, pinCol);
                ImGui.text(pin.label().isEmpty() ? " " : pin.label());
                ImGui.popStyleColor();
                NodeEditor.endPin();
            }
        }
    }

    private static void renderBeginNode(CanvasNode node, BlueprintNodeDefinition.NodeDef def) {
        // Bold, centered entry node — only an exec_out pin
        ImGui.textColored(0xFFFFFFFF, "[BEGIN]");
        ImGui.textDisabled("Execution starts here");

        // Single exec output pin
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) {
                ImGui.sameLine();
                ImGui.setCursorPosX(ImGui.getCursorPosX() + 60);
                NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())),
                        NodeEditorPinKind.Output);
                ImGui.pushStyleColor(ImGuiCol.Text, 0xFFFFFFFF);
                ImGui.text(">");
                ImGui.popStyleColor();
                NodeEditor.endPin();
            }
        }
    }

    private static void renderInputNode(CanvasNode node, BlueprintNodeDefinition.NodeDef def) {
        String paramName = node.inlineValues.getOrDefault("name", "???");
        String paramType = node.inlineValues.getOrDefault("type", "string");
        ImGui.textColored(pinColorForType(paramType) | 0xFF000000, "Input " + paramName);
        ImGui.textDisabled(paramType);

        // Single output pin
        NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, "value")),
                NodeEditorPinKind.Output);
        int pinCol = pinColorForType(paramType);
        ImGui.pushStyleColor(ImGuiCol.Text, pinCol);
        ImGui.text("-> " + paramName);
        ImGui.popStyleColor();
        NodeEditor.endPin();
    }

    // ═══════════════════════════════════════════════════════════════
    // Link creation
    // ═══════════════════════════════════════════════════════════════

    private static void createLink(CanvasNode fromNode, CanvasNode toNode,
                                    int fromPinIdx, int toPinIdx,
                                    BlueprintEditorCanvas graph) {
        BlueprintNodeDefinition.NodeDef fromDef = BlueprintNodeDefinition.get(fromNode.typeId);
        BlueprintNodeDefinition.NodeDef toDef = BlueprintNodeDefinition.get(toNode.typeId);
        if (fromDef == null || toDef == null) return;

        BlueprintNodeDefinition.PinDef fromPin = pinByIndex(fromDef, fromNode, fromPinIdx);
        BlueprintNodeDefinition.PinDef toPin = pinByIndex(toDef, toNode, toPinIdx);
        if (fromPin == null || toPin == null) return;

        if (fromPin.kind() == BlueprintNodeDefinition.PinKind.EXEC
                && toPin.kind() == BlueprintNodeDefinition.PinKind.EXEC) {
            // Exec link: output → input
            if (fromPin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT
                    && toPin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                graph.addExecEdge(fromNode.nodeId, fromPin.id(), toNode.nodeId, toPin.id());
                BlueprintEditorClientState.markDirty();
            }
        } else if (fromPin.kind() == BlueprintNodeDefinition.PinKind.DATA
                && toPin.kind() == BlueprintNodeDefinition.PinKind.DATA) {
            // Data link: output → input, with type compatibility check
            if (fromPin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT
                    && toPin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                if (typesCompatible(fromPin.typeKey(), toPin.typeKey())) {
                    graph.addDataEdge(fromNode.nodeId, fromPin.id(), toNode.nodeId, toPin.id());
                    BlueprintEditorClientState.markDirty();
                }
            }
        }
    }

    private static boolean typesCompatible(String from, String to) {
        if (from.equals(to)) return true;
        if ("any".equals(from) || "any".equals(to)) return true;
        if ("dynamic".equals(from) || "dynamic".equals(to)) return true;
        // Implicit conversions
        if ("int".equals(from) && "string".equals(to)) return true;
        if ("pos".equals(from) && "string".equals(to)) return true;
        if ("list<pos>".equals(from) && "list<any>".equals(to)) return true;
        if ("list<string>".equals(from) && "list<any>".equals(to)) return true;
        if ("list<item>".equals(from) && "list<any>".equals(to)) return true;
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // Context menus
    // ═══════════════════════════════════════════════════════════════

    private static void renderNodeContextMenu(BlueprintEditorCanvas graph) {
        if (!ImGui.isPopupOpen("node_context")) return;

        if (ImGui.beginPopup("node_context")) {
            int targetNodeId = ImGui.getStateStorage().getInt(ImGui.getID("ctx_node_id"));
            if (targetNodeId != 0) {
                CanvasNode node = graph.nodes.get((long) targetNodeId);
                if (node != null) {
                    ImGui.textColored(0xFFAAAAAA, node.getDisplayName());
                    ImGui.separator();

                    if (ImGui.button("Select")) {
                        BlueprintEditorClientState.setSelectedNodeId(targetNodeId);
                        ImGui.closeCurrentPopup();
                    }
                    if (ImGui.button("Duplicate")) {
                        float nx = node.posX + 50;
                        float ny = node.posY + 50;
                        CanvasNode copy = graph.createNode(node.typeId, nx, ny);
                        copy.inlineValues.putAll(node.inlineValues);
                        copy.dynamicPinCounts.putAll(node.dynamicPinCounts);
                        BlueprintEditorClientState.markDirty();
                        ImGui.closeCurrentPopup();
                    }
                    if (ImGui.button("Delete")) {
                        graph.removeNode(targetNodeId);
                        if (BlueprintEditorClientState.getSelectedNodeId() == targetNodeId) {
                            BlueprintEditorClientState.clearSelection();
                        }
                        BlueprintEditorClientState.markDirty();
                        ImGui.closeCurrentPopup();
                    }
                }
            }
            ImGui.endPopup();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Search palette
    // ═══════════════════════════════════════════════════════════════

    private static void renderSearchPalette(BlueprintEditorCanvas graph) {
        ImGui.openPopup("search_palette");
        if (ImGui.beginPopup("search_palette")) {
            ImGui.text("Create Node");

            // Search input
            searchBuf.set(BlueprintEditorClientState.getSearchQuery());
            ImGui.pushItemWidth(200);
            if (ImGui.inputText("##search", searchBuf)) {
                BlueprintEditorClientState.setSearchQuery(searchBuf.get());
            }
            ImGui.popItemWidth();

            // Set keyboard focus to search input
            if (ImGui.isWindowAppearing()) {
                ImGui.setKeyboardFocusHere(-1);
            }

            ImGui.separator();

            // Filtered results
            String query = BlueprintEditorClientState.getSearchQuery();
            List<BlueprintNodeDefinition.NodeDef> results = BlueprintNodeDefinition.search(query);

            // Group by category
            String currentCat = null;
            float mouseX = ImGui.getMousePosX();
            float mouseY = ImGui.getMousePosY();

            ImGui.beginChild("##search_results", 0, 200, true);
            for (BlueprintNodeDefinition.NodeDef def : results) {
                if (!def.category().equals(currentCat)) {
                    currentCat = def.category();
                    ImGui.textColored(0xFFAAAAAA, "--- " + currentCat + " ---");
                }

                ImGui.pushStyleColor(ImGuiCol.Header, def.color());
                if (ImGui.selectable("  " + def.displayName() + "  [" + def.typeId() + "]")) {
                    // Create node at canvas position (or mouse position as fallback)
                    float cx = NodeEditor.toCanvasX(mouseX);
                    float cy = NodeEditor.toCanvasY(mouseY);
                    graph.createNode(def.typeId(), cx, cy);
                    BlueprintEditorClientState.markDirty();
                    BlueprintEditorClientState.setSearchPaletteOpen(false);
                    BlueprintEditorClientState.setSearchQuery("");
                    ImGui.closeCurrentPopup();
                }
                ImGui.popStyleColor();
            }
            ImGui.endChild();

            if (ImGui.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape)) {
                BlueprintEditorClientState.setSearchPaletteOpen(false);
                BlueprintEditorClientState.setSearchQuery("");
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        } else {
            // Popup was closed externally
            BlueprintEditorClientState.setSearchPaletteOpen(false);
        }
    }

    private static void renderLoadPopup(BlueprintEditorCanvas graph) {
        ImGui.openPopup("load_blueprint");
        // Center the popup
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2 - 300, io.getDisplaySizeY() / 2 - 200,
                ImGuiCond.Always);
        ImGui.setNextWindowSize(600, 400, ImGuiCond.Always);

        if (ImGui.beginPopupModal("load_blueprint", ImGuiWindowFlags.NoResize)) {
            ImGui.text("Paste blueprint JSON below and click Import:");
            ImGui.separator();

            ImGui.pushItemWidth(-1);
            ImGui.inputTextMultiline("##loadJson", loadJsonBuf, 580, 280);

            ImGui.popItemWidth();
            ImGui.separator();

            if (ImGui.button("Import", 120, 0)) {
                String json = loadJsonBuf.get();
                if (!json.isBlank()) {
                    BlueprintEditorController.doLoadFromJson(json);
                    loadPopupOpen = false;
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel", 120, 0) || ImGui.isKeyPressed(ImGuiKey.Escape)) {
                loadPopupOpen = false;
            }

            ImGui.endPopup();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Inspector panel
    // ═══════════════════════════════════════════════════════════════

    private static void renderInspector(BlueprintEditorCanvas graph) {
        var io = ImGui.getIO();
        float x = io.getDisplaySizeX() - INSPECTOR_WIDTH;
        float y = TOP_BAR_HEIGHT + 4;
        ImGui.setNextWindowPos(x, y, ImGuiCond.Always);
        ImGui.setNextWindowSize(INSPECTOR_WIDTH, io.getDisplaySizeY() - y, ImGuiCond.Always);

        int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoResize;

        if (ImGui.begin("Inspector", flags)) {
            long selId = BlueprintEditorClientState.getSelectedNodeId();
            if (selId >= 0) {
                CanvasNode selNode = graph.nodes.get(selId);
                if (selNode != null) {
                    renderNodeInspector(selNode, graph);
                } else {
                    renderBlueprintInspector(graph);
                }
            } else {
                renderBlueprintInspector(graph);
            }
        }
        ImGui.end();
    }

    /** Inspector for blueprint metadata (no node selected). */
    private static void renderBlueprintInspector(BlueprintEditorCanvas graph) {
        ImGui.textColored(0xFFFFD700 | 0xFF000000, "Blueprint Properties");

        ImGui.pushItemWidth(-1);
        idBuf.set(graph.blueprintId);
        if (ImGui.inputText("ID", idBuf)) {
            graph.blueprintId = idBuf.get();
            BlueprintEditorClientState.markDirty();
        }
        nameBuf.set(graph.displayName);
        if (ImGui.inputText("Display Name", nameBuf)) {
            graph.displayName = nameBuf.get();
            BlueprintEditorClientState.markDirty();
        }
        descBuf.set(graph.description);
        if (ImGui.inputText("Description", descBuf)) {
            graph.description = descBuf.get();
            BlueprintEditorClientState.markDirty();
        }
        ImGui.popItemWidth();

        ImGui.separator();
        ImGui.text("Parameters");

        // List existing params
        List<String> toRemove = new ArrayList<>();
        for (var entry : graph.params.entrySet()) {
            ImGui.bulletText(entry.getKey() + " : " + paramTypeToString(entry.getValue()));
            ImGui.sameLine();
            if (ImGui.smallButton("X##" + entry.getKey())) {
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            BlueprintEditorClientState.removeParam(key);
        }

        // Add new param
        ImGui.pushItemWidth(120);
        ImGui.inputText("##newParamName", paramNameBuf);
        ImGui.sameLine();
        ImGui.combo("##newParamType", paramTypeIdx, PARAM_TYPES);
        ImGui.sameLine();
        boolean doAdd = ImGui.smallButton("+ Add");
        // Also add on Enter key in the text field
        if (ImGui.isItemFocused() && ImGui.isKeyPressed(ImGuiKey.Enter)) {
            doAdd = true;
        }
        if (doAdd) {
            String pn = paramNameBuf.get();
            if (!pn.isEmpty()) {
                ParamType pt = ParamType.parse(PARAM_TYPES[paramTypeIdx.get()]);
                if (pt != null) {
                    BlueprintEditorClientState.addParam(pn, pt);
                    paramNameBuf.set("");  // clear only on successful add
                }
            }
        }
        ImGui.popItemWidth();
    }

    /** Inspector for a selected node. */
    private static void renderNodeInspector(CanvasNode node, BlueprintEditorCanvas graph) {
        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        if (def == null) {
            ImGui.text("Unknown node: " + node.typeId);
            return;
        }

        int nodeColor = def.color() | 0xFF000000;
        ImGui.textColored(nodeColor, def.displayName());
        ImGui.textDisabled("Type: " + node.typeId + " | ID: " + node.nodeId);

        ImGui.separator();

        // Delete button (prominent, for when keyboard delete doesn't work)
        if (ImGui.button("Delete Node")) {
            long delId = node.nodeId;
            BlueprintEditorClientState.clearSelection();
            graph.removeNode(delId);
            BlueprintEditorClientState.markDirty();
        }

        ImGui.separator();

        // Editable inline values
        boolean isLiteral = node.typeId.startsWith("literal_");
        boolean isVar = "var".equals(node.typeId);

        if (isLiteral) {
            ImGui.text("Value:");
            inlineValBuf.set(node.inlineValues.getOrDefault("value", ""));
            ImGui.pushItemWidth(-1);
            if (ImGui.inputText("##inlineVal", inlineValBuf)) {
                node.inlineValues.put("value", inlineValBuf.get());
                BlueprintEditorClientState.markDirty();
            }
            ImGui.popItemWidth();
        }

        if (isVar) {
            ImGui.text("Variable name:");
            inlineValBuf.set(node.inlineValues.getOrDefault("name", ""));
            ImGui.pushItemWidth(-1);
            if (ImGui.inputText("##varName", inlineValBuf)) {
                node.inlineValues.put("name", inlineValBuf.get());
                BlueprintEditorClientState.markDirty();
            }
            ImGui.popItemWidth();

            // Show available vars
            ImGui.textDisabled("Available:");
            for (var entry : graph.params.entrySet()) {
                if (ImGui.selectable("  $" + entry.getKey())) {
                    node.inlineValues.put("name", entry.getKey());
                    BlueprintEditorClientState.markDirty();
                }
            }
        }

        if ("log".equals(node.typeId)) {
            String level = node.inlineValues.getOrDefault("level", "info");
            int levelIdx = java.util.Arrays.asList(LOG_LEVELS).indexOf(level);
            if (levelIdx < 0) levelIdx = 0;
            ImInt li = new ImInt(levelIdx);
            ImGui.combo("Level", li, LOG_LEVELS);
            if (li.get() != levelIdx) {
                node.inlineValues.put("level", LOG_LEVELS[li.get()]);
                BlueprintEditorClientState.markDirty();
            }
        }

        if ("block_interact".equals(node.typeId)) {
            String action = node.inlineValues.getOrDefault("action", "toggle");
            int actionIdx = java.util.Arrays.asList(INTERACT_ACTIONS).indexOf(action);
            if (actionIdx < 0) actionIdx = 0;
            ImInt ai = new ImInt(actionIdx);
            ImGui.combo("Action", ai, INTERACT_ACTIONS);
            if (ai.get() != actionIdx) {
                node.inlineValues.put("action", INTERACT_ACTIONS[ai.get()]);
                BlueprintEditorClientState.markDirty();
            }
        }

        if ("field_access".equals(node.typeId)) {
            String field = node.inlineValues.getOrDefault("field", "x");
            int fieldIdx = java.util.Arrays.asList(FIELD_OPTIONS).indexOf(field);
            if (fieldIdx < 0) fieldIdx = 0;
            ImInt fi = new ImInt(fieldIdx);
            ImGui.combo("Field", fi, FIELD_OPTIONS);
            if (fi.get() != fieldIdx) {
                node.inlineValues.put("field", FIELD_OPTIONS[fi.get()]);
                BlueprintEditorClientState.markDirty();
            }
        }

        // Data pins overview
        ImGui.separator();
        ImGui.text("Data Pins:");
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                boolean connected = graph.findDataSource(node.nodeId, pin.id()) != null;
                String status = connected ? "(connected)" : "(unconnected)";
                int statusCol = connected ? 0xFF55E164 : 0xFFAAAAAA;
                ImGui.textColored(statusCol, "  " + pin.label() + " : " + pin.typeKey() + " " + status);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Keyboard shortcuts
    // ═══════════════════════════════════════════════════════════════

    // ── GLFW window handle (set by host: ImGuiManager or standalone) ──
    private static long glfwWindow;

    /** Set the GLFW window handle for raw keyboard input. Called by the host at init time. */
    public static void setWindowHandle(long window) {
        glfwWindow = window;
    }

    // Track previous key states for edge detection (GLFW raw keys)
    private static final boolean[] prevKeyDown = new boolean[512];

    private static void handleShortcuts(BlueprintEditorCanvas graph) {
        var io = ImGui.getIO();
        boolean ctrl = io.getKeyCtrl();
        boolean capturingKeyboard = io.getWantCaptureKeyboard();

        // Delete → remove selected node (always works, even when capturing)
        if (ImGui.isKeyPressed(ImGuiKey.Delete, false)) {
            long selId = BlueprintEditorClientState.getSelectedNodeId();
            if (selId >= 0) {
                graph.removeNode(selId);
                BlueprintEditorClientState.clearSelection();
                BlueprintEditorClientState.markDirty();
                return;
            }
        }

        // Escape → clear selection / close palette (always works)
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            BlueprintEditorClientState.clearSelection();
            if (BlueprintEditorClientState.isSearchPaletteOpen()) {
                BlueprintEditorClientState.setSearchPaletteOpen(false);
            }
            return;
        }

        // Below shortcuts only when not typing in a text field
        if (capturingKeyboard) return;

        // Ctrl+S → save (raw GLFW edge detection)
        if (ctrl && isGlfwKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_S)) {
            BlueprintEditorController.doSave();
        }

        // P → create Place node
        if (isGlfwKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_P) && !ctrl) {
            float cx = NodeEditor.toCanvasX(io.getMousePosX());
            float cy = NodeEditor.toCanvasY(io.getMousePosY());
            graph.createNode("place", cx, cy);
            BlueprintEditorClientState.markDirty();
        }

        // V → create Var node
        if (isGlfwKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_V) && !ctrl) {
            float cx = NodeEditor.toCanvasX(io.getMousePosX());
            float cy = NodeEditor.toCanvasY(io.getMousePosY());
            graph.createNode("var", cx, cy);
            BlueprintEditorClientState.markDirty();
        }

        // F → create ForEach node
        if (isGlfwKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F) && !ctrl) {
            float cx = NodeEditor.toCanvasX(io.getMousePosX());
            float cy = NodeEditor.toCanvasY(io.getMousePosY());
            graph.createNode("for_each", cx, cy);
            BlueprintEditorClientState.markDirty();
        }

        // I → create If node
        if (isGlfwKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_I) && !ctrl) {
            float cx = NodeEditor.toCanvasX(io.getMousePosX());
            float cy = NodeEditor.toCanvasY(io.getMousePosY());
            graph.createNode("if", cx, cy);
            BlueprintEditorClientState.markDirty();
        }
    }

    /** Rising-edge detection for a GLFW key. */
    private static boolean isGlfwKeyJustPressed(int glfwKey) {
        if (glfwWindow == 0) return false;
        boolean down = org.lwjgl.glfw.GLFW.glfwGetKey(glfwWindow, glfwKey) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean prev = prevKeyDown[glfwKey];
        prevKeyDown[glfwKey] = down;
        return down && !prev;
    }

    // ═══════════════════════════════════════════════════════════════
    // Pin helpers
    // ═══════════════════════════════════════════════════════════════

    /** Get all pins in render order: exec-in, data-in, exec-out, data-out. */
    private static List<BlueprintNodeDefinition.PinDef> getInputPins(
            BlueprintNodeDefinition.NodeDef def, CanvasNode node) {
        List<BlueprintNodeDefinition.PinDef> result = new ArrayList<>();
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) result.add(pin);
        }
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) result.add(pin);
            // Dynamic pins
            if (pin.dynamic() && pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                int extra = node.dynamicPinCounts.getOrDefault(pin.id(), 0);
                for (int i = 1; i <= extra; i++) {
                    result.add(new BlueprintNodeDefinition.PinDef(
                            pin.id() + "_" + i,
                            pin.label() + " " + (i + 1),
                            pin.typeKey(),
                            BlueprintNodeDefinition.PinDir.INPUT,
                            BlueprintNodeDefinition.PinKind.DATA,
                            false));
                }
            }
        }
        return result;
    }

    private static List<BlueprintNodeDefinition.PinDef> getOutputPins(
            BlueprintNodeDefinition.NodeDef def, CanvasNode node) {
        List<BlueprintNodeDefinition.PinDef> result = new ArrayList<>();
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) result.add(pin);
        }
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) result.add(pin);
        }
        return result;
    }

    /** Compute total pin count (static + dynamic expansion) up to output pins. */
    private static int totalPinsBefore(BlueprintNodeDefinition.NodeDef def, CanvasNode node, BlueprintNodeDefinition.PinDir side) {
        int count = 0;
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) count++;
        }
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                count++;
                if (pin.dynamic()) {
                    count += node.dynamicPinCounts.getOrDefault(pin.id(), 0);
                }
            }
        }
        if (side == BlueprintNodeDefinition.PinDir.OUTPUT) {
            for (var pin : def.execPins()) {
                if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) count++;
            }
        }
        return count;
    }

    /** Get the 0-based pin index for a named pin on a node. Includes dynamic pin offsets. */
    static int pinIndexOf(BlueprintNodeDefinition.NodeDef def, CanvasNode node, String pinId) {
        int idx = 0;
        // Exec inputs (no dynamic)
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                if (pin.id().equals(pinId)) return idx;
                idx++;
            }
        }
        // Data inputs (with dynamic pin expansion accounting)
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                if (pin.dynamic()) {
                    if (pin.id().equals(pinId)) return idx;
                    int extra = node.dynamicPinCounts.getOrDefault(pin.id(), 0);
                    for (int i = 1; i <= extra; i++) {
                        String dynId = pin.id() + "_" + i;
                        if (dynId.equals(pinId)) return idx + i;
                    }
                    idx += 1 + extra; // base + all dynamic instances consume slots
                } else {
                    if (pin.id().equals(pinId)) return idx;
                    idx++;
                }
            }
        }
        // Exec outputs (no dynamic)
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) {
                if (pin.id().equals(pinId)) return idx;
                idx++;
            }
        }
        // Data outputs (no dynamic)
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) {
                if (pin.id().equals(pinId)) return idx;
                idx++;
            }
        }
        return -1;
    }

    /** Quick entry: get pin index with node context. */
    static int pinIndexOf(CanvasNode node, String pinId) {
        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        if (def == null) return 0;
        return pinIndexOf(def, node, pinId);
    }

    /** Get pin type key for a named pin. */
    private static String pinTypeKey(CanvasNode node, String pinId) {
        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        if (def == null) return "any";
        for (var pin : def.execPins()) {
            if (pin.id().equals(pinId)) return pin.typeKey();
        }
        for (var pin : def.dataPins()) {
            if (pin.id().equals(pinId)) return pin.typeKey();
        }
        return "any";
    }

    /** Get pin by index (includes dynamic pins). Must match pinIndexOf accounting. */
    static BlueprintNodeDefinition.PinDef pinByIndex(
            BlueprintNodeDefinition.NodeDef def, CanvasNode node, int index) {
        int idx = 0;
        // Exec inputs (no dynamic)
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                if (idx == index) return pin;
                idx++;
            }
        }
        // Data inputs (with dynamic pin expansion — consumes 1+extra slots per dynamic base)
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                if (idx == index) return pin;
                idx++;
                if (pin.dynamic()) {
                    int extra = node.dynamicPinCounts.getOrDefault(pin.id(), 0);
                    for (int i = 1; i <= extra; i++) {
                        if (idx == index) return new BlueprintNodeDefinition.PinDef(
                                pin.id() + "_" + i,
                                pin.label() + " " + (i + 1),
                                pin.typeKey(),
                                BlueprintNodeDefinition.PinDir.INPUT,
                                BlueprintNodeDefinition.PinKind.DATA,
                                false);
                        idx++;
                    }
                }
            }
        }
        // Exec outputs (no dynamic)
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) {
                if (idx == index) return pin;
                idx++;
            }
        }
        // Data outputs (no dynamic)
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) {
                if (idx == index) return pin;
                idx++;
            }
        }
        return null;
    }

    /** Map our pin direction/kind to NodeEditor pin kind. */
    private static int mapPinKind(BlueprintNodeDefinition.PinDef pin) {
        return pin.dir() == BlueprintNodeDefinition.PinDir.INPUT
                ? NodeEditorPinKind.Input : NodeEditorPinKind.Output;
    }

    // ═══════════════════════════════════════════════════════════════
    // Color helpers
    // ═══════════════════════════════════════════════════════════════

    /** Get the ABGR color for a type key (used for data pin coloring). */
    static int pinColorForType(String typeKey) {
        return switch (typeKey) {
            case "string" -> 0xFF5B8DFF;      // light blue
            case "int" -> 0xFF4CDFFF;         // golden yellow
            case "pos" -> 0xFF55E164;         // green
            case "list<pos>", "list<string>", "list<any>", "list<item>" -> 0xFF36A2FF;  // orange
            case "map<string,string>" -> 0xFFAF69EE;  // purple
            case "bool" -> 0xFF2DC0FB;        // dark yellow
            case "exec" -> 0xFFFFFFFF;        // white
            default -> 0xFFAAAAAA;            // gray
        };
    }

    /** Get RGBA float[4] for a type key (used for NodeEditor link coloring). */
    static float[] pinColorRGBA(String typeKey) {
        return switch (typeKey) {
            case "string" -> new float[]{0.357f, 0.553f, 1.0f, 1.0f};
            case "int" -> new float[]{0.298f, 0.875f, 1.0f, 1.0f};
            case "pos" -> new float[]{0.333f, 0.882f, 0.392f, 1.0f};
            case "list<pos>", "list<string>", "list<any>", "list<item>" -> new float[]{0.933f, 0.635f, 0.0f, 1.0f};
            case "map<string,string>" -> new float[]{0.686f, 0.412f, 0.933f, 1.0f};
            case "bool" -> new float[]{0.176f, 0.753f, 0.984f, 1.0f};
            case "exec" -> new float[]{1.0f, 1.0f, 1.0f, 1.0f};
            default -> new float[]{0.6f, 0.6f, 0.6f, 1.0f};
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // Misc helpers
    // ═══════════════════════════════════════════════════════════════

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 2) + "..";
    }

    private static int nodeCategoryOrder(CanvasNode node) {
        return switch (BlueprintNodeDefinition.get(node.typeId).category()) {
            case BlueprintNodeDefinition.CATEGORY_ENTRY -> -1;  // Begin always first
            case BlueprintNodeDefinition.CATEGORY_INPUT -> 0;
            case BlueprintNodeDefinition.CATEGORY_STEP -> 1;
            case BlueprintNodeDefinition.CATEGORY_EXPR -> 2;
            default -> 3;
        };
    }

    private static String paramTypeToString(ParamType type) {
        if (type instanceof ParamType.StringType) return "string";
        if (type instanceof ParamType.IntType) return "int";
        if (type instanceof ParamType.PosType) return "pos";
        if (type instanceof ParamType.ListPosType) return "list<pos>";
        if (type instanceof ParamType.ListStringType) return "list<string>";
        if (type instanceof ParamType.MapStringStringType) return "map<string,string>";
        return "string";
    }
}
