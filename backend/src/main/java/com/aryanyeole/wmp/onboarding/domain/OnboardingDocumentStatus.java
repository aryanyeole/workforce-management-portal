package com.aryanyeole.wmp.onboarding.domain;

/** Mirrors the CHECK constraint on onboarding_documents.status. */
public enum OnboardingDocumentStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}
