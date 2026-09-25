package com.herasgarden.gardenpost;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailRecoveryTest {
    @Test
    void emptyPhysicalDeliveryIsSafeToRetry() {
        assertEquals(
                MailService.RecoveryDisposition.SAFE_RETRY,
                MailService.recoveryDisposition(Set.of(), 2));
    }

    @Test
    void completePhysicalDeliveryFinalizes() {
        assertEquals(
                MailService.RecoveryDisposition.FINALIZE_DELIVERED,
                MailService.recoveryDisposition(Set.of(-1, 0, 1), 2));
    }

    @Test
    void partialPhysicalDeliveryRequiresReview() {
        assertEquals(
                MailService.RecoveryDisposition.REVIEW_REQUIRED,
                MailService.recoveryDisposition(Set.of(-1, 1), 2));
    }

    @Test
    void unexpectedExtraTaggedPartRequiresReview() {
        assertEquals(
                MailService.RecoveryDisposition.REVIEW_REQUIRED,
                MailService.recoveryDisposition(Set.of(-1, 0, 1, 9), 2));
    }
}
