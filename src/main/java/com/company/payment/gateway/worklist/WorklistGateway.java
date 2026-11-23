package com.company.payment.gateway.worklist;

import com.company.payment.gateway.worklist.dto.WorklistTaskCreateRequest;
import com.company.payment.gateway.worklist.dto.WorklistTaskResponse;

/** REST-only adapter for the external Worklist Service. */
public interface WorklistGateway {
    WorklistTaskResponse createTask(WorklistTaskCreateRequest request);

    /** Calls GET /api/v1/worklist/tasks/{taskCode}?status=...; the supplied endpoint has no response body. */
    void getTaskByCode(String taskCode, String status);

    void updateTaskStatus(Long taskId, String statusCode);
}
