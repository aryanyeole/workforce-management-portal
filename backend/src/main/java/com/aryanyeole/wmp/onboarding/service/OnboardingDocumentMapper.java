package com.aryanyeole.wmp.onboarding.service;

import com.aryanyeole.wmp.onboarding.api.DocumentResponse;
import com.aryanyeole.wmp.onboarding.domain.OnboardingDocument;

final class OnboardingDocumentMapper {

    private OnboardingDocumentMapper() {
    }

    static DocumentResponse toResponse(OnboardingDocument document) {
        return new DocumentResponse(
                document.getId(),
                document.getEmployee().getId(),
                document.getDocumentType(),
                document.getFileName(),
                document.getContentType(),
                document.getFileSizeBytes(),
                document.getStatus(),
                document.getUploadedAt());
    }
}
