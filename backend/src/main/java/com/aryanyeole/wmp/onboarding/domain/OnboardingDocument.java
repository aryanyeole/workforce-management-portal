package com.aryanyeole.wmp.onboarding.domain;

import com.aryanyeole.wmp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "onboarding_documents")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "document_type", nullable = false)
    private String documentType;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private OnboardingDocumentStatus status = OnboardingDocumentStatus.PENDING_REVIEW;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** V4 migration — the MIME type validated at upload time, kept for display/audit. */
    @Column(name = "content_type")
    private String contentType;

    /** V4 migration. */
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;
}
