package com.aryanyeole.wmp.onboarding.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "wmp.onboarding.document-storage")
public record DocumentStorageProperties(
        String basePath,
        DataSize maxFileSize,
        Set<String> allowedContentTypes) {
}
