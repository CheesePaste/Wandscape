package com.wsteam.wandscape.content.task.runtime;

import com.wsteam.wandscape.core.types.GridPos;
/** Approval metadata for large tasks. */
public record ApprovalInfo(
        GridPos suggestedPosition,
        long deadline,
        boolean autoApproved
) {}
