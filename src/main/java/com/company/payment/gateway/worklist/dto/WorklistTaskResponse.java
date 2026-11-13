package com.company.payment.gateway.worklist.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorklistTaskResponse {
    private Long wklTaskId;
    private Long wklTaskDefId;
    private String taskCd;
    private String taskName;
    private String refEntityName;
    private String refEntityId;
    private String linkedInstEntityName;
    private Long linkedInstEntityId;
    private Long createdBy;
    private LocalDateTime createdTs;
    private String statusCode;
    private String statusName;
}
