package com.company.payment.gateway.worklist;

import com.company.payment.gateway.worklist.dto.WorklistTaskCreateRequest;
import com.company.payment.gateway.worklist.dto.WorklistTaskResponse;

public interface WorklistGateway {
    WorklistTaskResponse createTask(WorklistTaskCreateRequest request);
    WorklistTaskResponse getTask(Long taskId);
    void updateTaskStatus(Long taskId, String statusCode);
}
