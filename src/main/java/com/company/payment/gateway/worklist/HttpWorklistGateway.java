package com.company.payment.gateway.worklist;

import com.company.payment.gateway.worklist.dto.WorklistTaskCreateRequest;
import com.company.payment.gateway.worklist.dto.WorklistTaskResponse;
import com.company.payment.gateway.worklist.dto.WorklistTaskStatusUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class HttpWorklistGateway implements WorklistGateway {
    private final RestClient worklistRestClient;

    @Value("${worklist.service.task-create-path:/api/v1/worklist/tasks}")
    private String createPath;

    @Value("${worklist.service.task-by-id-path:/api/v1/worklist/tasks/{taskId}}")
    private String getPath;

    @Value("${worklist.service.task-status-path:/api/v1/worklist/tasks/{taskId}/status}")
    private String statusPath;

    @Override
    public WorklistTaskResponse createTask(WorklistTaskCreateRequest request) {
        return worklistRestClient.post()
                .uri(createPath)
                .body(request)
                .retrieve()
                .body(WorklistTaskResponse.class);
    }

    @Override
    public WorklistTaskResponse getTask(Long taskId) {
        return worklistRestClient.get()
                .uri(getPath, taskId)
                .retrieve()
                .body(WorklistTaskResponse.class);
    }

    @Override
    public void updateTaskStatus(Long taskId, String statusCode) {
        worklistRestClient.put()
                .uri(statusPath, taskId)
                .body(new WorklistTaskStatusUpdateRequest(statusCode))
                .retrieve()
                .toBodilessEntity();
    }
}
