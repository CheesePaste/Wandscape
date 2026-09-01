package com.wsteam.wandscape.content.task.types;

/**
 * Interaction action type for BlockInteractOp.
 */
public record InteractAction(String id) {

    public static final InteractAction TOGGLE = new InteractAction("toggle");
    public static final InteractAction ACTIVATE = new InteractAction("activate");
    public static final InteractAction OPEN_GUI = new InteractAction("open_gui");

    @Override
    public String toString() {
        return id;
    }
}
