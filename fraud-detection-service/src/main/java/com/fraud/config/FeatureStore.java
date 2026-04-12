package com.fraud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
public class FeatureStore {

    @Value("${fraud.features.file-path}")
    private String filePath;

    private final AtomicReference<Set<String>> featureRef = new AtomicReference<>(Set.of());

    private long lastModified = 0;

    // Load at startup
    @PostConstruct
    public void init() {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        reload();
    }

    public boolean isAllowed(String key) {
        return featureRef.get().contains(key);
    }

    public void reload() {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Path path = Paths.get(filePath);

            Set<String> newSet = Files.lines(path)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            featureRef.set(newSet);

            lastModified = Files.getLastModifiedTime(path).toMillis();

            System.out.println("Features reloaded: " + newSet.size());

        } catch (IOException e) {
            System.err.println("Failed to reload features: " + e.getMessage());
        }
    }
    @Scheduled(cron = "0 0 7 * * *") // every day at 7 AM
    public void autoReload() {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Path path = Paths.get(filePath);

            long current = Files.getLastModifiedTime(path).toMillis();

            if (current > lastModified) {
                reload();
            }

        } catch (IOException e) {
            System.err.println("Error checking file: " + e.getMessage());
        }
    }
}