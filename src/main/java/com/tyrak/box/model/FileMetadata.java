package com.tyrak.box.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "files")
@Data
public class FileMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id; 

    @Column(nullable = false)
    private String name; 

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType; 

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes; 

    @Column(name = "user_id", nullable = false)
    private UUID userId; 

    @Column(name = "folder_id")
    private UUID folderId; 

    @Column(name = "is_deleted")
    private boolean isDeleted = false; 

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
}