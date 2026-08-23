package com.aryanyeole.wmp.onboarding.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.aryanyeole.wmp.common.security.AuthPrincipal;
import com.aryanyeole.wmp.common.web.BadRequestException;
import com.aryanyeole.wmp.onboarding.api.DocumentResponse;
import com.aryanyeole.wmp.onboarding.domain.Employee;
import com.aryanyeole.wmp.onboarding.domain.OnboardingDocument;
import com.aryanyeole.wmp.onboarding.domain.OnboardingDocumentStatus;
import com.aryanyeole.wmp.onboarding.repository.OnboardingDocumentRepository;

/**
 * No role checks here — route-level RBAC is RouteAuthorizationFilter's job
 * (ADR 0001); row visibility is EmployeeService.requireVisible, the same
 * mechanism used by employees and tasks.
 */
@Service
public class OnboardingDocumentService {

    private final OnboardingDocumentRepository onboardingDocumentRepository;
    private final EmployeeService employeeService;
    private final DocumentStorageService documentStorageService;

    public OnboardingDocumentService(OnboardingDocumentRepository onboardingDocumentRepository,
                                      EmployeeService employeeService,
                                      DocumentStorageService documentStorageService) {
        this.onboardingDocumentRepository = onboardingDocumentRepository;
        this.employeeService = employeeService;
        this.documentStorageService = documentStorageService;
    }

    @Transactional
    public DocumentResponse upload(AuthPrincipal principal, Long employeeId, String documentType, MultipartFile file) {
        Employee employee = employeeService.requireVisible(principal, employeeId);
        if (documentType == null || documentType.isBlank()) {
            throw new BadRequestException("documentType is required");
        }

        // Storage validation (content type, size) and the server-generated
        // key happen in DocumentStorageService before any DB row is written.
        String storagePath = documentStorageService.store(employeeId, file);

        OnboardingDocument document = new OnboardingDocument();
        document.setEmployee(employee);
        document.setDocumentType(documentType);
        document.setFileName(file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        document.setStoragePath(storagePath);
        document.setContentType(file.getContentType());
        document.setFileSizeBytes(file.getSize());
        document.setUploadedAt(Instant.now());
        document.setStatus(OnboardingDocumentStatus.PENDING_REVIEW);

        return OnboardingDocumentMapper.toResponse(onboardingDocumentRepository.save(document));
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(AuthPrincipal principal, Long employeeId) {
        employeeService.requireVisible(principal, employeeId);
        return onboardingDocumentRepository.findByEmployeeId(employeeId).stream()
                .map(OnboardingDocumentMapper::toResponse)
                .toList();
    }
}
