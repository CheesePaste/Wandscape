package com.wsteam.wandscape.shared.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.shared.data.AtomicStep.OperationA;
import com.wsteam.wandscape.shared.data.AtomicStep.OperationB;
import com.wsteam.wandscape.shared.data.AtomicStep.OperationC;
import com.wsteam.wandscape.shared.data.AtomicStep.OperationD;
import com.wsteam.wandscape.shared.data.ExecutionResult;

public interface AtomicExecutor {
    CompletableFuture<ExecutionResult> executeA(OperationA op, UUID npcId);
    CompletableFuture<ExecutionResult> executeB(OperationB op, UUID npcId);
    CompletableFuture<ExecutionResult> executeC(OperationC op, UUID npcId);
    CompletableFuture<ExecutionResult> executeD(OperationD op, UUID npcId);
}
