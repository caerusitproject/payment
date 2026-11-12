package com.company.payment.worklist;

import com.company.payment.domain.entity.WorklistItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST facade of the stand-in worklist. At most one OTP item is OPEN at any time. */
@RestController
@RequestMapping("/api/worklist")
@RequiredArgsConstructor
public class WorklistController {

    public record ItemView(Long id, String type, String title, String uniqueId, BigDecimal amount,
                           String status, String detail, Instant expiresTs) {
        static ItemView of(WorklistItem i) {
            return new ItemView(i.getId(), i.getItemType().name(), i.getTitle(), i.getUniqueId(), i.getAmount(),
                    i.getStatus().name(), i.getDetail(), i.getExpiresTs());
        }
    }

    public record CompleteOtpRequest(@NotBlank @Pattern(regexp = "\\d{4,10}", message = "OTP must be 4-10 digits") String otp) {}

    private final WorklistService service;

    @GetMapping("/otp/current")
    public ResponseEntity<ItemView> currentOtpItem() {
        return service.currentOtpItem().map(i -> ResponseEntity.ok(ItemView.of(i)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/otp/{id}/complete")
    public ItemView completeOtp(@PathVariable Long id, @Valid @RequestBody CompleteOtpRequest body) {
        return ItemView.of(service.completeOtp(id, body.otp()));
    }

    @GetMapping("/manual")
    public List<ItemView> manualItems() {
        return service.openManualItems().stream().map(ItemView::of).toList();
    }
}
