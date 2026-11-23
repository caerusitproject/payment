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

    @Value("${worklist.service.task-by-code-path:/api/v1/worklist/tasks/{taskCode}}")
    private String taskByCodePath;

    @Value("${worklist.service.task-status-path:/api/v1/worklist/tasks/{taskId}/status}")
    private String statusPath;

    @Override
    public WorklistTaskResponse createTask(WorklistTaskCreateRequest request) {
        return worklistRestClient.post().uri(createPath).body(request)
                .retrieve().body(WorklistTaskResponse.class);
    }

    /** Uses the Worklist API contract GET /tasks/{taskCode}?status=... */
    @Override
    public void getTaskByCode(String taskCode, String status) {
        worklistRestClient.get()
                .uri(builder -> {
                    var uri = builder.path(taskByCodePath.replace("{taskCode}", taskCode));
                    if (status != null && !status.isBlank()) uri.queryParam("status", status);
                    return uri.build();
                })
                .retrieve().toBodilessEntity();
    }

    @Override
    public void updateTaskStatus(Long taskId, String statusCode) {
        worklistRestClient.put().uri(statusPath, taskId)
                .body(new WorklistTaskStatusUpdateRequest(statusCode))
                .retrieve().toBodilessEntity();
    }
}
