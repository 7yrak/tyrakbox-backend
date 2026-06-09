package com.tyrak.box.controller;

import com.tyrak.box.model.File;
import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.model.UploadJob;
import com.tyrak.box.repository.UserRepository;
import com.tyrak.box.service.FileService;
import com.tyrak.box.service.UploadJobService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileService fileService;
    private final UserRepository userRepository;
    private final UploadJobService uploadJobService;

    public FileController(FileService fileService, UserRepository userRepository, UploadJobService uploadJobService) {
        this.fileService = fileService;
        this.userRepository = userRepository;
        this.uploadJobService = uploadJobService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) String folderIdStr,
            Authentication authentication) {
        
        try {
            User user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
            
            Folder folder = null;
            if (folderIdStr != null && !folderIdStr.isEmpty() && !folderIdStr.equals("undefined") && !folderIdStr.equals("null")) {
                UUID folderId = UUID.fromString(folderIdStr);
                folder = fileService.resolveFolderForUser(folderId, user.getId());
            }
            
            UploadJobService.UploadJobResult result = uploadJobService.submit(file, user, folder);
            return ResponseEntity.accepted().body(result);
        } catch (IOException e) {
            log.error("Error subiendo archivo para user={} folderId={}", authentication.getName(), folderIdStr, e);
            return ResponseEntity.internalServerError()
                    .body("Error al subir el archivo: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Error de validación subiendo archivo para user={} folderId={}", authentication.getName(), folderIdStr, e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/upload-jobs/{jobId}")
    public ResponseEntity<?> getUploadJob(@PathVariable UUID jobId, Authentication authentication) {
        User requestUser = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        UploadJobService.UploadJobResult result = uploadJobService.getJob(jobId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/upload-jobs")
    public ResponseEntity<?> getUploadJobs(Authentication authentication) {
        User requestUser = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        List<UploadJob> jobs = uploadJobService.getRecentJobsForUser(requestUser.getId());
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/upload-jobs/report")
    public ResponseEntity<?> getUploadJobsReport(Authentication authentication) {
        User requestUser = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        List<UploadJob> jobs = uploadJobService.getRecentJobsForUser(requestUser.getId());
        StringBuilder report = new StringBuilder();
        report.append("Reporte de carga de archivos\n");
        report.append("Usuario: ").append(requestUser.getUsername()).append("\n\n");
        for (UploadJob job : jobs) {
            report.append(job.getCreatedAt()).append(" | ")
                    .append(job.getOriginalFilename()).append(" | ")
                    .append(job.getStatus()).append(" | ")
                    .append(job.getMessage() != null ? job.getMessage() : "")
                    .append("\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reporte-upload.txt\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(report.toString());
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadFile(@PathVariable UUID id, Authentication authentication) {
        User requestUser = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        File file = fileService.getFileMetadata(id, requestUser);
        Resource resource = fileService.loadFileAsResource(id);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable UUID id, Authentication authentication) {
        User requestUser = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        fileService.deleteFile(id, requestUser);
        return ResponseEntity.noContent().build();
    }
}
