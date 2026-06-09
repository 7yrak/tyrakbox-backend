package com.tyrak.box.service;

import com.tyrak.box.model.File;
import com.tyrak.box.model.Folder;
import com.tyrak.box.model.UploadJob;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.UploadJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class UploadJobService {

    private static final Path UPLOAD_STAGING_DIR = Path.of(System.getProperty("java.io.tmpdir"), "tyrakbox", "upload-staging");

    public static class UploadJobResult {
        private final UUID jobId;
        private final UploadJob.Status status;
        private final String message;
        private final File file;

        public UploadJobResult(UUID jobId, UploadJob.Status status, String message, File file) {
            this.jobId = jobId;
            this.status = status;
            this.message = message;
            this.file = file;
        }

        public UUID getJobId() { return jobId; }
        public UploadJob.Status getStatus() { return status; }
        public String getMessage() { return message; }
        public File getFile() { return file; }
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final FileService fileService;
    private final UploadJobRepository uploadJobRepository;

    public UploadJobService(FileService fileService, UploadJobRepository uploadJobRepository) {
        this.fileService = fileService;
        this.uploadJobRepository = uploadJobRepository;
    }

    public UploadJobResult submit(MultipartFile multipartFile, String relativePath, User user, Folder folder) throws IOException {
        UploadJob job = new UploadJob();
        job.setUser(user);
        job.setFolder(folder);
        job.setOriginalFilename(relativePath != null && !relativePath.isBlank()
                ? relativePath
                : (multipartFile.getOriginalFilename() != null ? multipartFile.getOriginalFilename() : "archivo"));
        job.setStatus(UploadJob.Status.PENDING);
        job.setMessage("En cola");
        job = uploadJobRepository.save(job);

        Files.createDirectories(UPLOAD_STAGING_DIR);
        Path tempFile = Files.createTempFile(UPLOAD_STAGING_DIR, "tyrak-upload-", ".tmp");
        multipartFile.transferTo(tempFile);

        UploadJob savedJob = job;
        String originalPath = savedJob.getOriginalFilename();
        CompletableFuture.runAsync(() -> processJob(savedJob.getId(), tempFile, originalPath, user, folder), executor);
        return new UploadJobResult(savedJob.getId(), savedJob.getStatus(), savedJob.getMessage(), null);
    }

    public UploadJobResult getJob(UUID jobId) {
        UploadJob job = uploadJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job no encontrado"));
        return new UploadJobResult(job.getId(), job.getStatus(), job.getMessage(), job.getFile());
    }

    public List<UploadJob> getActiveJobsForUser(UUID userId) {
        return uploadJobRepository.findActiveByUserId(userId);
    }

    public List<UploadJob> getRecentJobsForUser(UUID userId) {
        return uploadJobRepository.findRecentByUserId(userId);
    }

    private void processJob(UUID jobId, Path tempFile, String originalPath, User user, Folder folder) {
        try {
            updateStatus(jobId, UploadJob.Status.PROCESSING, "Procesando", null);
            MultipartFile diskFile = new DiskBackedMultipartFile(tempFile.getFileName().toString(), tempFile);
            File saved = fileService.uploadFile(diskFile, originalPath, user, folder);
            updateStatus(jobId, UploadJob.Status.COMPLETED, "Completado", saved);
        } catch (Exception e) {
            updateStatus(jobId, UploadJob.Status.FAILED, e.getMessage() != null ? e.getMessage() : "Error inesperado", null);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    private void updateStatus(UUID jobId, UploadJob.Status status, String message, File file) {
        uploadJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setMessage(message);
            job.setFile(file);
            uploadJobRepository.save(job);
        });
    }

}
