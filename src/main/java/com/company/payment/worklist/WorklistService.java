package com.company.payment.worklist;

import com.company.payment.common.crypto.FieldCipher;
import com.company.payment.domain.entity.WorklistItem;
import com.company.payment.domain.enums.WorklistItemStatus;
import com.company.payment.domain.enums.WorklistItemType;
import com.company.payment.domain.repository.WorklistItemRepository;
import com.company.payment.service.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What the Accounts team's screen talks to. */
@Service
@RequiredArgsConstructor
public class WorklistService {

    private final WorklistItemRepository items;
    private final FieldCipher cipher;

    public Optional<WorklistItem> currentOtpItem() {
        return items.findFirstByItemTypeAndStatusOrderByIdAsc(WorklistItemType.OTP_ENTRY, WorklistItemStatus.OPEN);
    }

    public List<WorklistItem> openManualItems() {
        return items.findByItemTypeAndStatusOrderByIdAsc(WorklistItemType.MANUAL_INTERVENTION, WorklistItemStatus.OPEN);
    }

    @Transactional
    public WorklistItem completeOtp(Long id, String otp) {
        WorklistItem item = items.findById(id)
                .filter(i -> i.getItemType() == WorklistItemType.OTP_ENTRY)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OTP work item not found"));
        if (item.getStatus() != WorklistItemStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "Work item is " + item.getStatus());
        }
        if (Instant.now().isAfter(item.getExpiresTs())) {
            // The processor marks it EXPIRED and sends the payment to a retry queue with a fresh OTP.
            throw new ApiException(HttpStatus.GONE, "OTP window expired; a fresh OTP is generated on retry");
        }
        item.setOtpEncrypted(cipher.encrypt(otp));
        item.setStatus(WorklistItemStatus.COMPLETED);
        item.setCompletedTs(Instant.now());
        return items.save(item);
    }
}
