package com.aryanyeole.wmp.onboarding.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.aryanyeole.wmp.common.web.BadRequestException;
import com.aryanyeole.wmp.onboarding.config.DocumentStorageProperties;

/**
 * Writes uploaded document bytes to the local filesystem; only metadata
 * (see OnboardingDocument) is ever persisted in the database.
 *
 * The storage key is generated entirely server-side — employeeId (a
 * server-controlled Long) and a random UUID, never the client-supplied
 * filename — so there is no client input in the path at all, and thus no
 * possible directory-escape via it. The post-resolve containment check
 * below is defense in depth, documenting that invariant rather than
 * actually being reachable given how the key is built.
 */
@Service
public class DocumentStorageService {

    private final DocumentStorageProperties properties;
    private final Path basePath;

    public DocumentStorageService(DocumentStorageProperties properties) {
        this.properties = properties;
        this.basePath = Path.of(properties.basePath()).toAbsolutePath().normalize();
    }

    /**
     * Validates content type and size, writes the file, and returns the
     * storage key to persist as OnboardingDocument.storagePath.
     */
    public String store(Long employeeId, MultipartFile file) {
        validate(file);

        String storageKey = employeeId + "/" + UUID.randomUUID();
        Path target = basePath.resolve(storageKey).normalize();
        if (!target.startsWith(basePath)) {
            throw new IllegalStateException("Resolved storage path escaped the storage directory: " + target);
        }

        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded document", e);
        }

        return storageKey;
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("A non-empty file is required");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw new BadRequestException(
                    "File exceeds the maximum allowed size of " + properties.maxFileSize());
        }
        String contentType = file.getContentType();
        if (contentType == null || !properties.allowedContentTypes().contains(contentType)) {
            throw new BadRequestException("Unsupported content type: " + contentType);
        }
    }
}
