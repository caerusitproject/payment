package com.company.payment.worklist;

import com.company.payment.common.crypto.FieldCipher;
import com.company.payment.domain.entity.WorklistItem;
import com.company.payment.domain.enums.WorklistItemStatus;
import com.company.payment.domain.enums.WorklistItemType;
import com.company.payment.domain.repository.WorklistItemRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Database-backed worklist used until the real worklist module is plugged in. */
@Component
@RequiredArgsConstructor
public class DbWorklistGateway implements WorklistGateway {

    private final WorklistItemRepository items;
    private final FieldCipher cipher;

    @Override
    public void createOtpItem(OtpWorkItem i) {
        items.save(WorklistItem.builder()
                .itemType(WorklistItemType.OTP_ENTRY)
                .pymntReqId(i.pymntReqId())
                .pymntTxnId(i.pymntTxnId())
                .uniqueId(i.uniqueId())
                .title(i.title())
                .amount(i.amount())
                .status(WorklistItemStatus.OPEN)
                .createdTs(Instant.now())
                .expiresTs(i.expiresTs())
                .build());
    }

    @Override
    public OtpStatus otpStatus(Long pymntTxnId) {
        Optional<WorklistItem> found = items.findFirstByPymntTxnIdAndItemTypeOrderByIdDesc(pymntTxnId, WorklistItemType.OTP_ENTRY);
        if (found.isEmpty()) return new OtpStatus(OtpStatus.State.MISSING, null);
        WorklistItem item = found.get();
        if (item.getStatus() == WorklistItemStatus.OPEN && Instant.now().isAfter(item.getExpiresTs())) {
            item.setStatus(WorklistItemStatus.EXPIRED);
            items.save(item);
        }
        return switch (item.getStatus()) {
            case OPEN -> new OtpStatus(OtpStatus.State.OPEN, null);
            case COMPLETED -> new OtpStatus(OtpStatus.State.COMPLETED, cipher.decrypt(item.getOtpEncrypted()));
            case EXPIRED -> new OtpStatus(OtpStatus.State.EXPIRED, null);
            case CONSUMED -> new OtpStatus(OtpStatus.State.CONSUMED, null);
        };
    }

    @Override
    public void consumeOtp(Long pymntTxnId) {
        items.findFirstByPymntTxnIdAndItemTypeOrderByIdDesc(pymntTxnId, WorklistItemType.OTP_ENTRY).ifPresent(item -> {
            item.setOtpEncrypted(null);
            item.setStatus(WorklistItemStatus.CONSUMED);
            items.save(item);
        });
    }

    @Override
    public void createManualInterventionItem(ManualWorkItem m) {
        if (items.existsByPymntReqIdAndItemTypeAndStatus(m.pymntReqId(), WorklistItemType.MANUAL_INTERVENTION, WorklistItemStatus.OPEN)) {
            return;
        }
        items.save(WorklistItem.builder()
                .itemType(WorklistItemType.MANUAL_INTERVENTION)
                .pymntReqId(m.pymntReqId())
                .pymntTxnId(m.pymntTxnId())
                .title(m.title())
                .detail(m.reason() != null && m.reason().length() > 1000 ? m.reason().substring(0, 1000) : m.reason())
                .status(WorklistItemStatus.OPEN)
                .createdTs(Instant.now())
                .build());
    }
}
