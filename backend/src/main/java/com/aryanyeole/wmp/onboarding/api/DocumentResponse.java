package com.aryanyeole.wmp.onboarding.api;

import java.time.Instant;

import com.aryanyeole.wmp.onboarding.domain.OnboardingDocumentStatus;

/** Metadata only — never file contents or the internal storage path. */
public record DocumentResponse(
        Long id,
        Long employeeId,
        String documentType,
        String fileName,
        String contentType,
        Long fileSizeBytes,
        OnboardingDocumentStatus status,
        Instant uploadedAt) {
}
