package com.company.payment.service;

import com.company.payment.exception.NoEligiblePaymentModeException;

import com.company.payment.domain.entity.PaymentMode;
import com.company.payment.domain.enums.PaymentModeCode;
import com.company.payment.domain.repository.PaymentModeRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides the payment mode; the caller never chooses it.
 * <ol>
 *   <li>Only active modes whose [min_amount, max_amount] band contains the amount are eligible.</li>
 *   <li>Beneficiary at ICICI (IFSC starts with ICIC) and FT eligible: FT (ICICI to ICICI).</li>
 *   <li>Otherwise the most specific band wins, i.e. the eligible non-FT mode with the highest min_amount
 *       (RTGS from 2 lakh upwards, NEFT below that).</li>
 * </ol>
 * RTGS cut-off hours are enforced by the bank; an out-of-hours rejection lands in a retry queue.
 */
@Component
@RequiredArgsConstructor

/**
 * Resolves the appropriate payment mode based on the payment amount
 * and the beneficiary's bank IFSC code.
 *
 * <p>The resolver first identifies all active payment modes whose
 * configured minimum and maximum amount limits support the requested
 * payment amount.</p>
 *
 * <p>Business rule:
 * <ul>
 *     <li>If the beneficiary has an ICICI Bank account, prefer FT
 *         (Fund Transfer) when FT is eligible.</li>
 *     <li>If FT is not applicable, select another eligible payment mode.</li>
 *     <li>For other banks, select the eligible non-FT payment mode
 *         with the highest minimum amount.</li>
 * </ul>
 *
 * <p>This class reads payment mode configurations from the database.
 * It does not directly communicate with the ICICI Bank API.</p>
 */
public class PaymentModeResolver {

    /**
     * IFSC prefix used to identify ICICI Bank beneficiary accounts.
     *
     * ICICI Bank IFSC codes start with "ICIC".
     */
    private static final String ICICI_IFSC_PREFIX = "ICIC";

    /**
     * Repository used to fetch active payment mode configurations
     * from the database.
     */
    private final PaymentModeRepository modes;

    /**
     * Determines the appropriate payment mode for a payment request.
     *
     * @param amount          payment amount requested by the user
     * @param beneficiaryIfsc beneficiary bank IFSC code
     * @return the selected eligible payment mode
     * @throws NoEligiblePaymentModeException if no suitable payment
     *                                        mode is configured for the amount
     */
    public PaymentMode resolve(BigDecimal amount, String beneficiaryIfsc) {

        // Step 1: Fetch all active payment modes from the database.
        // Only payment modes marked as active are considered.
        List<PaymentMode> eligible = modes.findByActiveStatusCdidTrue().stream()

                // Step 2: Filter payment modes based on the requested amount.
                // The amount must be greater than or equal to the minimum
                // amount and less than or equal to the maximum amount.
                .filter(m -> amount.compareTo(m.getMinAmount()) >= 0
                        && amount.compareTo(m.getMaxAmount()) <= 0)

                // Collect the eligible payment modes into a list.
                .toList();

        // Step 3: Check whether the beneficiary belongs to ICICI Bank.
        //
        // First, ensure the IFSC is not null.
        // Convert it to uppercase so that the comparison is
        // case-insensitive.
        // Then check whether the IFSC starts with "ICIC".
        boolean iciciBeneficiary = beneficiaryIfsc != null
                && beneficiaryIfsc.toUpperCase().startsWith(ICICI_IFSC_PREFIX);

        // Step 4: If the beneficiary belongs to ICICI Bank,
        // prefer FT (Fund Transfer), provided FT is eligible
        // for the requested payment amount.
        if (iciciBeneficiary) {

            // Search the eligible payment modes for FT.
            var ft = eligible.stream()
                    .filter(m -> PaymentModeCode.FT.name().equals(m.getModeCode()))
                    .findFirst();

            // If FT is available, return it immediately.
            // No other payment mode selection is required.
            if (ft.isPresent()) {
                return ft.get();
            }
        }

        // Step 5: If the beneficiary is not an ICICI account,
        // or FT is not eligible for an ICICI account,
        // select another eligible payment mode.
        return eligible.stream()

                // Exclude FT because this selection is for
                // non-FT payment modes.
                .filter(m -> !PaymentModeCode.FT.name().equals(m.getModeCode()))

                // Select the payment mode with the highest minimum amount.
                //
                // If two payment modes have the same minimum amount,
                // use payment mode ID as the secondary sorting criterion.
                // reverseOrder() makes the lower ID win in this tie-break.
                .max(Comparator.comparing(PaymentMode::getMinAmount)
                        .thenComparing(
                                PaymentMode::getPymntModeId,
                                Comparator.reverseOrder()
                        ))

                // If no eligible non-FT payment mode exists,
                // throw a business exception.
                .orElseThrow(() ->
                        new NoEligiblePaymentModeException(
                                "No active payment mode supports amount " + amount
                        )
                );
    }
}
