package org.magiccolony.core.task;

import org.magiccolony.core.types.GridPos;

/** Approval metadata for large tasks. */
public record ApprovalInfo(
        GridPos suggestedPosition,
        long deadline,
        boolean autoApproved
) {}
