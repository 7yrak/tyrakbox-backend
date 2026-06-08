package com.tyrak.box.controller;

import com.tyrak.box.model.File;
import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FolderRepository;
import com.tyrak.box.repository.UserRepository;
import com.tyrak.box.service.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final UserRepository userRepository;
    private final FolderRepository folderRepository;

    public FileController(FileService fileService, UserRepository userRepository, FolderRepository folderRepository) {
        this.fileService = fileService;
        this.userRepository = userRepository;
        this.folderRepository = folderRepository;
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
                folder = folderRepository.findById(folderId)
                        .orElseThrow(() -> new RuntimeException("Carpeta no encontrada"));
            }
            
            File savedFile = fileService.uploadFile(file, user, folder);
            
            return ResponseEntity.ok(savedFile);
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body("Error al subir el archivo: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
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
