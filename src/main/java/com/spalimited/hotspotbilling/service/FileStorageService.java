package com.spalimited.hotspotbilling.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Stores uploaded images in the local upload directory (served at /api/uploads). */
@Service
public class FileStorageService {

    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of(MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp", MediaType.IMAGE_GIF_VALUE);

    @Value("${app.upload-dir}")
    private String uploadDir;

    /** Validates and stores an image, returning the generated filename. */
    public String storeImage(MultipartFile photo) throws IOException {
        String contentType = photo.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only JPEG, PNG, WebP or GIF images are allowed");
        }
        String extension = switch (contentType) {
            case MediaType.IMAGE_PNG_VALUE -> ".png";
            case "image/webp" -> ".webp";
            case MediaType.IMAGE_GIF_VALUE -> ".gif";
            default -> ".jpg";
        };
        Path dir = Path.of(uploadDir).toAbsolutePath();
        Files.createDirectories(dir);
        String storedName = UUID.randomUUID() + extension;
        photo.transferTo(dir.resolve(storedName));
        return storedName;
    }
}
