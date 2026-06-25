package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.NpcTaskPackage;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-NPC task queue that stores {@link NpcTaskPackage}s instead of bare {@link AtomicOp}s.
 * Each package is a self-contained unit with its own stance position,
 * so the NPC navigates correctly when switching between packages.
 *
 * <h3>Queue structure</h3>
 * <pre>
 *   suspensionStack (LIFO) — interrupted packages waiting to resume
 *   currentPackage         — the package being executed right now
 *   pending (FIFO)         — queued packages waiting to start
 * </pre>
 *
 * <p>Execution priority: resume suspended → current → pending head.
 * Urgent packages (emergency, wand equip) are inserted at the front of pending.
 */
public class NpcTaskQueue {

    private static final int MAX_SUSPENSION_DEPTH = 3;

    private final Deque<NpcTaskPackage> pending = new ArrayDeque<>();
    private final Deque<SuspensionContext> suspensionStack = new ArrayDeque<>();

    @Nullable
    private NpcTaskPackage currentPackage;
    private int stepIndex;

    // ── Current package ──

    /** Set the package to execute right now, resetting stepIndex. */
    public void startPackage(NpcTaskPackage pkg) {
        this.currentPackage = pkg;
        this.stepIndex = pkg.startStepIndex();
    }

    @Nullable
    public NpcTaskPackage currentPackage() {
        return currentPackage;
    }

    public int stepIndex() {
        return stepIndex;
    }

    /** Set stepIndex directly (used when restoring from a loaded task). */
    public void setStepIndex(int index) {
        this.stepIndex = index;
    }

    /** Peek at the next op in the current package without consuming it. */
    @Nullable
    public AtomicOp peekCurrentOp() {
        if (currentPackage == null || currentPackage.isComplete(stepIndex)) return null;
        return currentPackage.get(stepIndex);
    }

    /** Advance one step in the current package. Returns true if the package is now complete. */
    public boolean advanceStep() {
        stepIndex++;
        if (currentPackage != null && currentPackage.isComplete(stepIndex)) {
            return true;
        }
        return false;
    }

    /** True when the current package has no more steps. */
    public boolean isCurrentPackageDone() {
        return currentPackage == null || currentPackage.isComplete(stepIndex);
    }

    // ── Pending queue ──

    /** Append a package to the back of the pending queue (normal order). */
    public void enqueueNormal(NpcTaskPackage pkg) {
        pending.addLast(pkg);
    }

    /** Insert a package at the front of the pending queue (emergency / wand equip). */
    public void enqueueUrgent(NpcTaskPackage pkg) {
        pending.addFirst(pkg);
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public int pendingSize() {
        return pending.size();
    }

    // ── Suspend / Resume ──

    /**
     * Suspend the current package, saving progress to the suspension stack.
     * The next call to {@link #finishCurrentPackage} or {@link #startNextPending}
     * will pick up from the front of pending or the suspension stack.
     *
     * @return the suspension context, or null if there is no current package
     */
    @Nullable
    public SuspensionContext suspendCurrent(long currentTick) {
        if (currentPackage == null) return null;
        if (suspensionStack.size() >= MAX_SUSPENSION_DEPTH) return null;

        SuspensionContext ctx = new SuspensionContext(currentPackage, stepIndex, currentTick);
        suspensionStack.push(ctx);
        currentPackage = null;
        stepIndex = 0;
        return ctx;
    }

    /** True if there are suspended packages waiting to resume. */
    public boolean hasSuspended() {
        return !suspensionStack.isEmpty();
    }

    /**
     * Pop the most recently suspended package and make it current.
     * The NPC should navigate back to the package's stance before resuming execution.
     */
    @Nullable
    public SuspensionContext resumeLatest() {
        if (suspensionStack.isEmpty()) return null;
        SuspensionContext ctx = suspensionStack.pop();
        this.currentPackage = ctx.pkg();
        this.stepIndex = ctx.stepIndex();
        return ctx;
    }

    // ── Release ──

    /**
     * Release the current package back to the global pool with preserved progress.
     * Used when the NPC can't continue (mana depleted, resource shortage, stuck).
     */
    @Nullable
    public NpcTaskPackage releaseCurrent() {
        if (currentPackage == null) return null;
        // Build a package with the current progress baked in
        NpcTaskPackage partial = new NpcTaskPackage(
                currentPackage.source(),
                currentPackage.sequence(),
                currentPackage.stance(),
                currentPackage.priority(),
                stepIndex  // preserved progress
        );
        currentPackage = null;
        stepIndex = 0;
        return partial;
    }

    // ── Completion ──

    /**
     * Finish the current package and load the next one.
     * Priority: resume suspended → pop pending head → nothing.
     */
    public void finishCurrentPackage() {
        currentPackage = null;
        stepIndex = 0;
        startNextPending();
    }

    /**
     * Clear the current package WITHOUT resuming or starting the next one.
     * Used when the NPC is released from its current task
     * (mana depletion, resource shortage) and should go idle.
     */
    public void clearCurrentWithoutResume() {
        currentPackage = null;
        stepIndex = 0;
    }

    /** Move the next pending or suspended package into current. */
    public void startNextPending() {
        // Resume suspended first (LIFO)
        if (!suspensionStack.isEmpty()) {
            resumeLatest();
            return;
        }
        // Then next queued package
        if (!pending.isEmpty()) {
            NpcTaskPackage pkg = pending.pollFirst();
            startPackage(pkg);
        }
    }

    // ── State queries ──

    /** NPC has no work at all. */
    public boolean isIdle() {
        return currentPackage == null && pending.isEmpty() && suspensionStack.isEmpty();
    }

    /** NPC has work (current package, pending, or suspended). */
    public boolean hasWork() {
        return currentPackage != null || !pending.isEmpty();
    }

    /** Total ops remaining across current + pending packages. */
    public int totalWorkRemaining() {
        int total = 0;
        if (currentPackage != null) {
            total += Math.max(0, currentPackage.size() - stepIndex);
        }
        for (NpcTaskPackage pkg : pending) {
            total += pkg.size();
        }
        return total;
    }

    public void clear() {
        currentPackage = null;
        stepIndex = 0;
        pending.clear();
        suspensionStack.clear();
    }
}
