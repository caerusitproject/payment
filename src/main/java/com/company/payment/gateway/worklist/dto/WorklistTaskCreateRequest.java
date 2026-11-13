package com.company.payment.gateway.worklist.dto;



import lombok.Builder;

@Builder
public record WorklistTaskCreateRequest(

        String taskCode,

        String taskName,

        String refEntityName,

        String refEntityId,

        String linkedInstEntityName,

        Long linkedInstEntityId,

        String assigneeRoleCd

) {
}