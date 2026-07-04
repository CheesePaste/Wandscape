package com.wsteam.wandscape.blueprint.editor;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.blueprint.editor.BlueprintEditorCanvas.CanvasNode;
import com.wsteam.wandscape.task.engine.dsl.ParamType;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.extension.nodeditor.NodeEditor;
import imgui.extension.nodeditor.NodeEditorContext;
import imgui.extension.nodeditor.flag.NodeEditorPinKind;
import imgui.type.ImLong;
import imgui.type.ImString;
import imgui.type.ImInt;

public final class BlueprintEditorImGui {

    private static boolean pendingAutoLayout = false;
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
        if (!BlueprintEditorClientState.isEditing()) return;

        BlueprintEditorCanvas graph = BlueprintEditorClientState.getCanvas();
        if (graph == null) return;

        var io = ImGui.getIO();
        float winW = io.getDisplaySizeX() - INSPECTOR_WIDTH;
        float winH = io.getDisplaySizeY();

        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(winW, winH, ImGuiCond.Always);

        int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoBringToFrontOnFocus;

        if (ImGui.begin("Blueprint Canvas", flags)) {
            renderTopBar(graph);
            renderNodeCanvas(graph, ctx);
        }
        ImGui.end();

        if (BlueprintEditorClientState.isInspectorVisible()) {
            renderInspector(graph);
        }

        if (BlueprintEditorClientState.isSearchPaletteOpen()) {
            renderSearchPalette(graph);
        }

        if (loadPopupOpen) {
            renderLoadPopup(graph);
        }
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
        if (ImGui.button("Layout (L)")) {
            pendingAutoLayout = true;
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
        // 处理刚加载时的初始坐标同步和视角居中
        if (BlueprintEditorClientState.consumeJustLoaded()) {
            for (CanvasNode n : graph.nodes.values()) {
                NodeEditor.setNodePosition(n.nodeId, n.posX, n.posY);
            }
            NodeEditor.navigateToContent(0.0f); // 瞬间居中
        }

// 处理一键自动排版 (按 L 键或点击按钮)
        if (pendingAutoLayout) {
            pendingAutoLayout = false;
            doAutoLayout(graph);
            NodeEditor.navigateToContent(0.5f); // 0.5秒动画居中平滑过渡
        }

        List<CanvasNode> sortedNodes = new ArrayList<>(graph.nodes.values());
        sortedNodes.sort((a, b) -> Integer.compare(nodeCategoryOrder(a), nodeCategoryOrder(b)));

        for (CanvasNode node : sortedNodes) {
            renderNode(node, graph);
        }

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

        // ── Draw colored links ──
        int uniqueLinkId = 1;
        for (BlueprintEditorCanvas.ExecEdge ee : graph.execEdges) {
            CanvasNode from = graph.nodes.get(ee.fromNodeId());
            CanvasNode to = graph.nodes.get(ee.toNodeId());
            if (from != null && to != null) {
                int fromIdx = pinIndexOf(from, ee.fromPinId());
                int toIdx = pinIndexOf(to, ee.toPinId());
                if (fromIdx >= 0 && toIdx >= 0) {
                    // Exec lines are solid white
                    NodeEditor.link(uniqueLinkId++, pinId(from.nodeId, fromIdx), pinId(to.nodeId, toIdx), 1.0f, 1.0f, 1.0f, 1.0f, 2.5f);
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
                    // Data lines color matching pin type
                    float[] rgba = pinColorRGBA(pinTypeKey(to, de.toPinId()));
                    NodeEditor.link(uniqueLinkId++, pinId(from.nodeId, fromIdx), pinId(to.nodeId, toIdx), rgba[0], rgba[1], rgba[2], rgba[3], 2.5f);
                }
            }
        }

        NodeEditor.suspend();
        final long ctxNodeId = NodeEditor.getNodeWithContextMenu();
        if (ctxNodeId != -1) {
            ImGui.openPopup("node_context");
            ImGui.getStateStorage().setInt(ImGui.getID("ctx_node_id"), (int) ctxNodeId);
        }
        renderNodeContextMenu(graph);

        if (NodeEditor.showBackgroundContextMenu()) {
            BlueprintEditorClientState.setSearchPaletteOpen(true);
            BlueprintEditorClientState.setSearchQuery("");
            searchBuf.set("");
        }

        NodeEditor.resume();
        NodeEditor.end();
        ImGui.popStyleColor();

        // 原生选择状态同步（修复选中面板一闪而过的 Bug）
        long[] selectedNodes = new long[1];
        if (NodeEditor.getSelectedNodes(selectedNodes, 1) > 0) {
            BlueprintEditorClientState.setSelectedNodeId(selectedNodes[0]);
        } else {
            if (NodeEditor.isBackgroundClicked()) {
                NodeEditor.clearSelection();
            }
            BlueprintEditorClientState.clearSelection();
        }

        handleShortcuts(graph);
    }

    // ═══════════════════════════════════════════════════════════════
    // Pin Render Helper
    // ═══════════════════════════════════════════════════════════════

    /** Draws the visual icon (circle or triangle) for a pin */
    private static void drawPinShape(boolean isExec, boolean connected, int color) {
        float size = 14f;
        ImGui.dummy(size, size); // Allocate layout space

        // Grab rect pos where dummy was drawn
        ImVec2 min = ImGui.getItemRectMin();
        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        float cx = min.x + size * 0.5f;
        float cy = min.y + size * 0.5f;

        if (isExec) {
            // Triangle for exec flow (pointing right)
            float h = size * 0.45f;
            float w = size * 0.35f;
            if (connected) {
                drawList.addTriangleFilled(cx - w, cy - h, cx - w, cy + h, cx + w, cy, color);
            } else {
                drawList.addTriangle(cx - w, cy - h, cx - w, cy + h, cx + w, cy, color, 1.5f);
            }
        } else {
            // Circle for data flow
            float r = size * 0.4f;
            if (connected) {
                drawList.addCircleFilled(cx, cy, r, color);
            } else {
                drawList.addCircle(cx, cy, r, color, 12, 1.5f);
            }
        }
    }

    private static boolean isPinConnected(BlueprintEditorCanvas graph, CanvasNode node, BlueprintNodeDefinition.PinDef pin) {
        if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
            if (pin.kind() == BlueprintNodeDefinition.PinKind.EXEC) {
                for (BlueprintEditorCanvas.ExecEdge e : graph.execEdges) {
                    if (e.toNodeId() == node.nodeId && e.toPinId().equals(pin.id())) return true;
                }
            } else {
                return graph.findDataEdgeTo(node.nodeId, pin.id()) != null;
            }
        } else {
            if (pin.kind() == BlueprintNodeDefinition.PinKind.EXEC) {
                return graph.findExecTarget(node.nodeId, pin.id()) != null;
            } else {
                for (BlueprintEditorCanvas.DataEdge e : graph.dataEdges) {
                    if (e.fromNodeId() == node.nodeId && e.fromPinId().equals(pin.id())) return true;
                }
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // Node rendering
    // ═══════════════════════════════════════════════════════════════

    private static void renderNode(CanvasNode node, BlueprintEditorCanvas graph) {
        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        if (def == null) return;

        ImGui.pushStyleColor(ImGuiCol.Header, def.color());
        NodeEditor.beginNode(node.nodeId);

        boolean isBegin = BlueprintNodeDefinition.CATEGORY_ENTRY.equals(def.category());
        boolean isExpr = BlueprintNodeDefinition.CATEGORY_EXPR.equals(def.category());
        boolean isInput = BlueprintNodeDefinition.CATEGORY_INPUT.equals(def.category());
        boolean isStep = BlueprintNodeDefinition.CATEGORY_STEP.equals(def.category());

        if (isBegin) {
            renderBeginNode(node, def, graph);
        } else if (isStep) {
            renderStepNode(node, def, graph);
        } else if (isExpr) {
            renderExprNode(node, def, graph);
        } else if (isInput) {
            renderInputNode(node, def, graph);
        }

        NodeEditor.endNode();
        ImGui.popStyleColor();
    }

    private static void renderStepNode(CanvasNode node, BlueprintNodeDefinition.NodeDef def, BlueprintEditorCanvas graph) {
        ImGui.textColored(def.color() | 0xFF000000, def.displayName());
        ImGui.spacing();

        List<BlueprintNodeDefinition.PinDef> inputPins = getInputPins(def, node);
        List<BlueprintNodeDefinition.PinDef> outputPins = getOutputPins(def, node);

        // Inputs column
        ImGui.beginGroup();
        for (BlueprintNodeDefinition.PinDef pin : inputPins) {
            NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())), mapPinKind(pin));
            boolean connected = isPinConnected(graph, node, pin);
            int pinCol = pinColorForType(pin.typeKey());

            drawPinShape(pin.kind() == BlueprintNodeDefinition.PinKind.EXEC, connected, pinCol);
            ImGui.sameLine();
            ImGui.pushStyleColor(ImGuiCol.Text, pinCol);
            ImGui.text(pin.label().isEmpty() ? pin.id() : pin.label());
            ImGui.popStyleColor();
            NodeEditor.endPin();
        }
        ImGui.endGroup();

        if (!outputPins.isEmpty()) {
            ImGui.sameLine();
            ImGui.dummy(30, 0); // Center padding
            ImGui.sameLine();

            // Outputs column
            ImGui.beginGroup();
            for (BlueprintNodeDefinition.PinDef pin : outputPins) {
                NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())), mapPinKind(pin));
                boolean connected = isPinConnected(graph, node, pin);
                int pinCol = pinColorForType(pin.typeKey());

                ImGui.pushStyleColor(ImGuiCol.Text, pinCol);
                ImGui.text(pin.label().isEmpty() ? pin.id() : pin.label());
                ImGui.popStyleColor();
                ImGui.sameLine();
                drawPinShape(pin.kind() == BlueprintNodeDefinition.PinKind.EXEC, connected, pinCol);
                NodeEditor.endPin();
            }
            ImGui.endGroup();
        }

        if (node.inlineValues.containsKey("var_name")) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0xFFFFC864);
            ImGui.text("[iter: $" + node.inlineValues.get("var_name") + "]");
            ImGui.popStyleColor();
        }
        if (node.inlineValues.containsKey("action")) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0xFF64B4FF);
            ImGui.text("[action: " + node.inlineValues.get("action") + "]");
            ImGui.popStyleColor();
        }
    }

    private static void renderExprNode(CanvasNode node, BlueprintNodeDefinition.NodeDef def, BlueprintEditorCanvas graph) {
        List<BlueprintNodeDefinition.PinDef> inputPins = getInputPins(def, node);
        List<BlueprintNodeDefinition.PinDef> outputPins = getOutputPins(def, node);

        if (!inputPins.isEmpty()) {
            ImGui.beginGroup();
            for (BlueprintNodeDefinition.PinDef pin : inputPins) {
                NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())), mapPinKind(pin));
                boolean connected = isPinConnected(graph, node, pin);
                int pinCol = pinColorForType(pin.typeKey());
                drawPinShape(false, connected, pinCol);
                NodeEditor.endPin();
            }
            ImGui.endGroup();
            ImGui.sameLine();
        }

        // Central name
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
            ImGui.beginGroup();
            for (BlueprintNodeDefinition.PinDef pin : outputPins) {
                NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())), mapPinKind(pin));
                boolean connected = isPinConnected(graph, node, pin);
                int pinCol = pinColorForType(pin.typeKey());
                drawPinShape(false, connected, pinCol);
                NodeEditor.endPin();
            }
            ImGui.endGroup();
        }
    }

    private static void renderBeginNode(CanvasNode node, BlueprintNodeDefinition.NodeDef def, BlueprintEditorCanvas graph) {
        ImGui.textColored(0xFFFFFFFF, "[BEGIN]");
        ImGui.textDisabled("Execution starts here");

        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) {
                ImGui.sameLine();
                NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, pin.id())), NodeEditorPinKind.Output);
                boolean connected = isPinConnected(graph, node, pin);
                drawPinShape(true, connected, 0xFFFFFFFF);
                NodeEditor.endPin();
            }
        }
    }

    private static void renderInputNode(CanvasNode node, BlueprintNodeDefinition.NodeDef def, BlueprintEditorCanvas graph) {
        String paramName = node.inlineValues.getOrDefault("name", "???");
        String paramType = node.inlineValues.getOrDefault("type", "string");
        int color = pinColorForType(paramType) | 0xFF000000;

        // Plain text label — just show the param name, editing is in Inspector
        if (paramName.isEmpty()) {
            ImGui.textColored(0xFF888888, "(unset)");
        } else {
            ImGui.textColored(color, "$" + paramName);
        }
        ImGui.textDisabled(paramType);

        // Output pin
        ImGui.sameLine();
        NodeEditor.beginPin(pinId(node.nodeId, pinIndexOf(def, node, "value")), NodeEditorPinKind.Output);
        boolean connected = isPinConnected(graph, node, def.dataPins().get(0));
        drawPinShape(false, connected, pinColorForType(paramType));
        NodeEditor.endPin();
    }

    // ═══════════════════════════════════════════════════════════════
    // Core Logic (Link checking, Menus, Keyboard...)
    // ═══════════════════════════════════════════════════════════════

    private static void createLink(CanvasNode fromNode, CanvasNode toNode, int fromPinIdx, int toPinIdx, BlueprintEditorCanvas graph) {
        BlueprintNodeDefinition.NodeDef fromDef = BlueprintNodeDefinition.get(fromNode.typeId);
        BlueprintNodeDefinition.NodeDef toDef = BlueprintNodeDefinition.get(toNode.typeId);
        if (fromDef == null || toDef == null) return;

        BlueprintNodeDefinition.PinDef fromPin = pinByIndex(fromDef, fromNode, fromPinIdx);
        BlueprintNodeDefinition.PinDef toPin = pinByIndex(toDef, toNode, toPinIdx);
        if (fromPin == null || toPin == null) return;

        if (fromPin.kind() == BlueprintNodeDefinition.PinKind.EXEC && toPin.kind() == BlueprintNodeDefinition.PinKind.EXEC) {
            if (fromPin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT && toPin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                graph.addExecEdge(fromNode.nodeId, fromPin.id(), toNode.nodeId, toPin.id());
                BlueprintEditorClientState.markDirty();
            }
        } else if (fromPin.kind() == BlueprintNodeDefinition.PinKind.DATA && toPin.kind() == BlueprintNodeDefinition.PinKind.DATA) {
            if (fromPin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT && toPin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
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
        if ("int".equals(from) && "string".equals(to)) return true;
        if ("pos".equals(from) && "string".equals(to)) return true;
        if ("list<pos>".equals(from) && "list<any>".equals(to)) return true;
        if ("list<string>".equals(from) && "list<any>".equals(to)) return true;
        if ("list<item>".equals(from) && "list<any>".equals(to)) return true;
        return false;
    }

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
                        NodeEditor.selectNode((long) targetNodeId, false);
                        ImGui.closeCurrentPopup();
                    }
                    if (ImGui.button("Duplicate")) {
                        CanvasNode copy = graph.createNode(node.typeId, node.posX + 50, node.posY + 50);
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

    private static void renderSearchPalette(BlueprintEditorCanvas graph) {
        ImGui.openPopup("search_palette");
        if (ImGui.beginPopup("search_palette")) {
            ImGui.text("Create Node");
            searchBuf.set(BlueprintEditorClientState.getSearchQuery());
            ImGui.pushItemWidth(200);
            if (ImGui.inputText("##search", searchBuf)) {
                BlueprintEditorClientState.setSearchQuery(searchBuf.get());
            }
            ImGui.popItemWidth();

            if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere(-1);
            ImGui.separator();

            List<BlueprintNodeDefinition.NodeDef> results = BlueprintNodeDefinition.search(BlueprintEditorClientState.getSearchQuery());
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
                    graph.createNode(def.typeId(), NodeEditor.toCanvasX(mouseX), NodeEditor.toCanvasY(mouseY));
                    BlueprintEditorClientState.markDirty();
                    BlueprintEditorClientState.setSearchPaletteOpen(false);
                    ImGui.closeCurrentPopup();
                }
                ImGui.popStyleColor();
            }
            ImGui.endChild();

            if (ImGui.button("Cancel") || ImGui.isKeyPressed(ImGuiKey.Escape)) {
                BlueprintEditorClientState.setSearchPaletteOpen(false);
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        } else {
            BlueprintEditorClientState.setSearchPaletteOpen(false);
        }
    }

    private static void renderLoadPopup(BlueprintEditorCanvas graph) {
        ImGui.openPopup("load_blueprint");
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2 - 300, io.getDisplaySizeY() / 2 - 200, ImGuiCond.Always);
        ImGui.setNextWindowSize(600, 400, ImGuiCond.Always);

        if (ImGui.beginPopupModal("load_blueprint", ImGuiWindowFlags.NoResize)) {
            ImGui.text("Paste blueprint JSON below and click Import:");
            ImGui.separator();
            ImGui.pushItemWidth(-1);
            ImGui.inputTextMultiline("##loadJson", loadJsonBuf, 580, 280);
            ImGui.popItemWidth();
            ImGui.separator();

            if (ImGui.button("Import", 120, 0)) {
                if (!loadJsonBuf.get().isBlank()) {
                    BlueprintEditorController.doLoadFromJson(loadJsonBuf.get());
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

    private static void renderInspector(BlueprintEditorCanvas graph) {
        var io = ImGui.getIO();
        float x = io.getDisplaySizeX() - INSPECTOR_WIDTH;
        float y = TOP_BAR_HEIGHT + 4;
        ImGui.setNextWindowPos(x, y, ImGuiCond.Always);
        ImGui.setNextWindowSize(INSPECTOR_WIDTH, io.getDisplaySizeY() - y, ImGuiCond.Always);

        int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize;

        if (ImGui.begin("Inspector", flags)) {
            long selId = BlueprintEditorClientState.getSelectedNodeId();
            if (selId >= 0 && graph.nodes.containsKey(selId)) {
                renderNodeInspector(graph.nodes.get(selId), graph);
            } else {
                renderBlueprintInspector(graph);
            }
        }
        ImGui.end();
    }

    private static void renderBlueprintInspector(BlueprintEditorCanvas graph) {
        ImGui.textColored(0xFFFFD700 | 0xFF000000, "Blueprint Properties");
        ImGui.pushItemWidth(-1);
        idBuf.set(graph.blueprintId);
        if (ImGui.inputText("ID", idBuf)) { graph.blueprintId = idBuf.get(); BlueprintEditorClientState.markDirty(); }
        nameBuf.set(graph.displayName);
        if (ImGui.inputText("Name", nameBuf)) { graph.displayName = nameBuf.get(); BlueprintEditorClientState.markDirty(); }
        descBuf.set(graph.description);
        if (ImGui.inputText("Desc", descBuf)) { graph.description = descBuf.get(); BlueprintEditorClientState.markDirty(); }
        ImGui.popItemWidth();
        ImGui.separator();
        ImGui.text("Parameters");

        List<String> toRemove = new ArrayList<>();
        if (graph.params.isEmpty()) {
            ImGui.textDisabled("  No parameters yet — add one below");
        } else {
            for (var entry : graph.params.entrySet()) {
                ImGui.bulletText(entry.getKey() + " : " + paramTypeToString(entry.getValue()));
                ImGui.sameLine();
                if (ImGui.smallButton("X##" + entry.getKey())) toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) BlueprintEditorClientState.removeParam(key);

        // Add new param — stacked layout (vertical fit within 260px panel)
        ImGui.pushItemWidth(-1);
        ImGui.inputText("##newParamName", paramNameBuf);
        boolean nameFieldFocused = ImGui.isItemFocused();
        ImGui.combo("##newParamType", paramTypeIdx, PARAM_TYPES);
        ImGui.popItemWidth();
        boolean enterPressed = nameFieldFocused && ImGui.isKeyPressed(ImGuiKey.Enter, false);
        if (ImGui.button("+ Add Param", -1, 0) || enterPressed) {
            String pn = paramNameBuf.get().trim();
            if (!pn.isEmpty()) {
                ParamType pt = ParamType.parse(PARAM_TYPES[paramTypeIdx.get()]);
                if (pt != null) {
                    BlueprintEditorClientState.addParam(pn, pt);
                    paramNameBuf.set("");
                }
            }
        }
    }

    private static void renderNodeInspector(CanvasNode node, BlueprintEditorCanvas graph) {
        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        if (def == null) return;
        ImGui.textColored(def.color() | 0xFF000000, def.displayName());
        ImGui.textDisabled("ID: " + node.nodeId);
        ImGui.separator();

        if (ImGui.button("Delete Node")) {
            BlueprintEditorClientState.clearSelection();
            NodeEditor.clearSelection();
            graph.removeNode(node.nodeId);
            BlueprintEditorClientState.markDirty();
        }
        ImGui.separator();

        if (node.typeId.startsWith("literal_")) {
            ImGui.text("Value:");
            inlineValBuf.set(node.inlineValues.getOrDefault("value", ""));
            ImGui.pushItemWidth(-1);
            if (ImGui.inputText("##inlineVal", inlineValBuf)) {
                node.inlineValues.put("value", inlineValBuf.get());
                BlueprintEditorClientState.markDirty();
            }
            ImGui.popItemWidth();
        }

        if ("var".equals(node.typeId)) {
            ImGui.text("Variable name:");
            inlineValBuf.set(node.inlineValues.getOrDefault("name", ""));
            ImGui.pushItemWidth(-1);
            if (ImGui.inputText("##varName", inlineValBuf)) {
                node.inlineValues.put("name", inlineValBuf.get());
                BlueprintEditorClientState.markDirty();
            }
            ImGui.popItemWidth();
            ImGui.textDisabled("Available:");
            for (var entry : graph.params.entrySet()) {
                if (ImGui.selectable("  $" + entry.getKey())) {
                    node.inlineValues.put("name", entry.getKey());
                    BlueprintEditorClientState.markDirty();
                }
            }
        }

        if ("input".equals(node.typeId)) {
            String curName = node.inlineValues.getOrDefault("name", "");
            String curType = node.inlineValues.getOrDefault("type", "string");

            ImGui.text("Parameter Name:");
            inlineValBuf.set(curName);
            ImGui.pushItemWidth(-1);
            if (ImGui.inputText("##inputParamName", inlineValBuf)) {
                node.inlineValues.put("name", inlineValBuf.get());
                BlueprintEditorClientState.markDirty();
            }
            ImGui.popItemWidth();

            ImGui.text("Type:");
            int typeIdx = java.util.Arrays.asList(PARAM_TYPES).indexOf(curType);
            if (typeIdx < 0) typeIdx = 0;
            ImInt ti = new ImInt(typeIdx);
            ImGui.combo("##inputParamType", ti, PARAM_TYPES);
            if (ti.get() != typeIdx) {
                node.inlineValues.put("type", PARAM_TYPES[ti.get()]);
                BlueprintEditorClientState.markDirty();
            }

            // Quick-select from existing params
            if (!graph.params.isEmpty()) {
                ImGui.textDisabled("Or pick from declared params:");
                for (var entry : graph.params.entrySet()) {
                    String label = entry.getKey() + " (" + paramTypeToString(entry.getValue()) + ")";
                    if (ImGui.selectable("  " + label)) {
                        node.inlineValues.put("name", entry.getKey());
                        node.inlineValues.put("type", paramTypeToString(entry.getValue()));
                        BlueprintEditorClientState.markDirty();
                    }
                }
            }
        }

        if ("for_each".equals(node.typeId)) {
            ImGui.text("Loop variable (iter):");
            inlineValBuf.set(node.inlineValues.getOrDefault("var_name", "it"));
            ImGui.pushItemWidth(-1);
            if (ImGui.inputText("##forEachVar", inlineValBuf)) {
                node.inlineValues.put("var_name", inlineValBuf.get());
                BlueprintEditorClientState.markDirty();
            }
            ImGui.popItemWidth();
        }

        if ("if".equals(node.typeId)) {
            boolean elseInvert = "true".equals(node.inlineValues.get("else_invert"));
            if (ImGui.checkbox("elseInvert (swap branches)", elseInvert)) {
                node.inlineValues.put("else_invert", elseInvert ? "false" : "true");
                BlueprintEditorClientState.markDirty();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Keyboard shortcuts
    // ═══════════════════════════════════════════════════════════════

    private static long glfwWindow;
    private static final boolean[] prevKeyDown = new boolean[512];

    public static void setWindowHandle(long window) { glfwWindow = window; }

    private static boolean isGlfwKeyJustPressed(int glfwKey) {
        if (glfwWindow == 0) return false;
        boolean down = org.lwjgl.glfw.GLFW.glfwGetKey(glfwWindow, glfwKey) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean prev = prevKeyDown[glfwKey];
        prevKeyDown[glfwKey] = down;
        return down && !prev;
    }

    private static void handleShortcuts(BlueprintEditorCanvas graph) {
        var io = ImGui.getIO();
        boolean ctrl = io.getKeyCtrl();
        if (!ctrl && isGlfwKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_L)) {
            pendingAutoLayout = true;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Delete, false)) {
            long selId = BlueprintEditorClientState.getSelectedNodeId();
            if (selId >= 0) {
                graph.removeNode(selId);
                BlueprintEditorClientState.clearSelection();
                NodeEditor.clearSelection();
                BlueprintEditorClientState.markDirty();
            }
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            BlueprintEditorClientState.clearSelection();
            NodeEditor.clearSelection();
            BlueprintEditorClientState.setSearchPaletteOpen(false);
            return;
        }

        if (io.getWantCaptureKeyboard()) return;

        if (ctrl && isGlfwKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_S)) {
            BlueprintEditorController.doSave();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private static List<BlueprintNodeDefinition.PinDef> getInputPins(BlueprintNodeDefinition.NodeDef def, CanvasNode node) {
        List<BlueprintNodeDefinition.PinDef> result = new ArrayList<>();
        for (var pin : def.execPins()) if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) result.add(pin);
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) result.add(pin);
            if (pin.dynamic() && pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                int extra = node.dynamicPinCounts.getOrDefault(pin.id(), 0);
                for (int i = 1; i <= extra; i++) result.add(new BlueprintNodeDefinition.PinDef(pin.id() + "_" + i, pin.label() + " " + (i + 1), pin.typeKey(), BlueprintNodeDefinition.PinDir.INPUT, BlueprintNodeDefinition.PinKind.DATA, false));
            }
        }
        return result;
    }

    private static List<BlueprintNodeDefinition.PinDef> getOutputPins(BlueprintNodeDefinition.NodeDef def, CanvasNode node) {
        List<BlueprintNodeDefinition.PinDef> result = new ArrayList<>();
        for (var pin : def.execPins()) if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) result.add(pin);
        for (var pin : def.dataPins()) if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) result.add(pin);
        return result;
    }

    static int pinIndexOf(BlueprintNodeDefinition.NodeDef def, CanvasNode node, String pinId) {
        int idx = 0;
        for (var pin : def.execPins()) { if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) { if (pin.id().equals(pinId)) return idx; idx++; } }
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                if (pin.dynamic()) {
                    if (pin.id().equals(pinId)) return idx;
                    int extra = node.dynamicPinCounts.getOrDefault(pin.id(), 0);
                    for (int i = 1; i <= extra; i++) if ((pin.id() + "_" + i).equals(pinId)) return idx + i;
                    idx += 1 + extra;
                } else {
                    if (pin.id().equals(pinId)) return idx;
                    idx++;
                }
            }
        }
        for (var pin : def.execPins()) { if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) { if (pin.id().equals(pinId)) return idx; idx++; } }
        for (var pin : def.dataPins()) { if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) { if (pin.id().equals(pinId)) return idx; idx++; } }
        return -1;
    }

    static int pinIndexOf(CanvasNode node, String pinId) {
        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        return def == null ? 0 : pinIndexOf(def, node, pinId);
    }

    private static String pinTypeKey(CanvasNode node, String pinId) {
        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        if (def == null) return "any";
        for (var pin : def.execPins()) if (pin.id().equals(pinId)) return pin.typeKey();
        for (var pin : def.dataPins()) if (pin.id().equals(pinId)) return pin.typeKey();
        return "any";
    }

    static BlueprintNodeDefinition.PinDef pinByIndex(BlueprintNodeDefinition.NodeDef def, CanvasNode node, int index) {
        int idx = 0;
        for (var pin : def.execPins()) { if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) { if (idx == index) return pin; idx++; } }
        for (var pin : def.dataPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.INPUT) {
                if (idx == index) return pin;
                idx++;
                if (pin.dynamic()) {
                    int extra = node.dynamicPinCounts.getOrDefault(pin.id(), 0);
                    for (int i = 1; i <= extra; i++) {
                        if (idx == index) return new BlueprintNodeDefinition.PinDef(pin.id() + "_" + i, pin.label() + " " + (i + 1), pin.typeKey(), BlueprintNodeDefinition.PinDir.INPUT, BlueprintNodeDefinition.PinKind.DATA, false);
                        idx++;
                    }
                }
            }
        }
        for (var pin : def.execPins()) { if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) { if (idx == index) return pin; idx++; } }
        for (var pin : def.dataPins()) { if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) { if (idx == index) return pin; idx++; } }
        return null;
    }

    private static int mapPinKind(BlueprintNodeDefinition.PinDef pin) {
        return pin.dir() == BlueprintNodeDefinition.PinDir.INPUT ? NodeEditorPinKind.Input : NodeEditorPinKind.Output;
    }

    static int pinColorForType(String typeKey) {
        return switch (typeKey) {
            case "string" -> 0xFF5B8DFF;
            case "int" -> 0xFF4CDFFF;
            case "pos" -> 0xFF55E164;
            case "list<pos>", "list<string>", "list<any>", "list<item>" -> 0xFF36A2FF;
            case "map<string,string>" -> 0xFFAF69EE;
            case "bool" -> 0xFF2DC0FB;
            case "exec" -> 0xFFFFFFFF;
            default -> 0xFFAAAAAA;
        };
    }

    static float[] pinColorRGBA(String typeKey) {
        return switch (typeKey) {
            case "string" -> new float[]{1.0f, 0.553f, 0.357f, 1.0f};  // ImGui format RGBA
            case "int" -> new float[]{1.0f, 0.875f, 0.298f, 1.0f};
            case "pos" -> new float[]{0.392f, 0.882f, 0.333f, 1.0f};
            case "list<pos>", "list<string>", "list<any>", "list<item>" -> new float[]{1.0f, 0.635f, 0.212f, 1.0f};
            case "map<string,string>" -> new float[]{0.933f, 0.412f, 0.686f, 1.0f};
            case "bool" -> new float[]{0.984f, 0.753f, 0.176f, 1.0f};
            case "exec" -> new float[]{1.0f, 1.0f, 1.0f, 1.0f};
            default -> new float[]{0.6f, 0.6f, 0.6f, 1.0f};
        };
    }

    private static String truncate(String s, int maxLen) {
        return (s == null) ? "" : (s.length() <= maxLen ? s : s.substring(0, maxLen - 2) + "..");
    }

    private static int nodeCategoryOrder(CanvasNode node) {
        return switch (BlueprintNodeDefinition.get(node.typeId).category()) {
            case BlueprintNodeDefinition.CATEGORY_ENTRY -> -1;
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
    // ═══════════════════════════════════════════════════════════════
    // Auto Layout Algorithm
    // ═══════════════════════════════════════════════════════════════

    private static void doAutoLayout(BlueprintEditorCanvas graph) {
        java.util.Set<Long> placed = new java.util.HashSet<>();

        // 1. 优先排版 Input 参数节点（固定在最左边区域）
        float inputY = 0;
        for (CanvasNode n : graph.nodes.values()) {
            if ("input".equals(n.typeId)) {
                setNodePos(n, -400, inputY);
                placed.add(n.nodeId);
                inputY += 120;
            }
        }

        // 2. 从 Begin 节点开始，沿着执行流向右铺开
        CanvasNode begin = graph.findBeginNode();
        if (begin != null) {
            layoutExecChain(begin, 0, 0, graph, placed);
        }

        // 3. 处理游离节点 (没连线的孤儿节点)，放在主逻辑下方
        float orphanX = 0;
        float orphanY = 800;
        for (CanvasNode n : graph.nodes.values()) {
            if (!placed.contains(n.nodeId)) {
                setNodePos(n, orphanX, orphanY);
                placed.add(n.nodeId);
                orphanX += 250;
                if (orphanX > 1500) { orphanX = 0; orphanY += 150; } // 换行
            }
        }
    }

    private static float layoutExecChain(CanvasNode node, float x, float y, BlueprintEditorCanvas graph, java.util.Set<Long> placed) {
        if (placed.contains(node.nodeId)) return y; // 防止死循环

        setNodePos(node, x, y);
        placed.add(node.nodeId);

        // 将给此节点提供数据的 Expression 节点排在它的左边
        layoutDataInputs(node, x - 250, y, graph, placed);

        BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(node.typeId);
        if (def == null) return y;

        // 收集所有执行流输出引脚
        List<String> outPins = new ArrayList<>();
        for (var pin : def.execPins()) {
            if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT) outPins.add(pin.id());
        }

        if (outPins.isEmpty()) return y;

        if (outPins.size() == 1) {
            // 单分支，直接向右推进
            CanvasNode target = graph.findExecTarget(node.nodeId, outPins.get(0));
            if (target != null) {
                return layoutExecChain(target, x + 280, y, graph, placed);
            }
            return y;
        } else {
            // 多分支 (If / Parallel 等)，垂直向下排开
            float currentY = y;
            for (String pinId : outPins) {
                CanvasNode target = graph.findExecTarget(node.nodeId, pinId);
                if (target != null) {
                    currentY = layoutExecChain(target, x + 300, currentY, graph, placed);
                    currentY += 160; // 增加分支之间的间距
                }
            }
            return currentY;
        }
    }

    private static float layoutDataInputs(CanvasNode node, float x, float startY, BlueprintEditorCanvas graph, java.util.Set<Long> placed) {
        float currentY = startY;
        for (BlueprintEditorCanvas.DataEdge edge : graph.dataEdges) {
            if (edge.toNodeId() == node.nodeId) {
                CanvasNode source = graph.nodes.get(edge.fromNodeId());
                if (source != null && !placed.contains(source.nodeId)) {
                    setNodePos(source, x, currentY);
                    placed.add(source.nodeId);

                    // 递归将其依赖的变量/表达式推到更左边
                    layoutDataInputs(source, x - 220, currentY, graph, placed);

                    currentY += 100;
                }
            }
        }
        return currentY;
    }

    private static void setNodePos(CanvasNode node, float x, float y) {
        node.posX = x;
        node.posY = y;
        // 同步给底层的 ImGui Node Editor
        NodeEditor.setNodePosition(node.nodeId, x, y);
    }
}