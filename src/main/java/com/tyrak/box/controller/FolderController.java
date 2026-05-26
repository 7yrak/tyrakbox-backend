package com.tyrak.box.controller;

import com.tyrak.box.model.File;
import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FileRepository;
import com.tyrak.box.repository.FolderRepository;
import com.tyrak.box.repository.UserRepository;
import com.tyrak.box.service.FolderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/folders")
@CrossOrigin(origins = "http://localhost:4200")
public class FolderController {

    private final FolderService folderService;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;

    public FolderController(FolderService folderService, UserRepository userRepository, FileRepository fileRepository, FolderRepository folderRepository) {
        this.folderService = folderService;
        this.userRepository = userRepository;
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
    }

    @GetMapping("/content")
    public ResponseEntity<Map<String, Object>> getFolderContent(
            @RequestParam(required = false) String parentIdStr,
            Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        UUID parentId = null;
        if (parentIdStr != null && !parentIdStr.isEmpty() && !parentIdStr.equals("undefined") && !parentIdStr.equals("null")) {
             parentId = UUID.fromString(parentIdStr);
        }

        List<Folder> folders;
        List<File> files;
        
        if (parentId == null) {
            folders = folderRepository.findRootFoldersForUser(user.getId());
            files = fileRepository.findRootFilesForUser(user.getId());
        } else {
            folders = folderRepository.findSubFoldersByParentId(user.getId(), parentId);
            files = fileRepository.findFilesByFolderId(user.getId(), parentId);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("folders", folders);
        response.put("files", files);

        return ResponseEntity.ok(response);
    }


    @PostMapping
    public ResponseEntity<Folder> createFolder(
            @RequestBody Map<String, String> payload,
            Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        
        String name = payload.get("name");
        String parentIdStr = payload.get("parentId");
        UUID parentId = parentIdStr != null && !parentIdStr.isEmpty() && !parentIdStr.equals("undefined") && !parentIdStr.equals("null") ? UUID.fromString(parentIdStr) : null;
        
        // La creación de carpetas ahora es manejada por el FileService para consistencia
        // pero podemos exponer un endpoint para crear carpetas vacías si es necesario.
        // Por ahora, lo dejamos así para no romper la lógica del frontend.
        Folder folder = new Folder();
        folder.setName(name);
        folder.setUser(user);
        if (parentId != null) {
            folder.setParent(folderRepository.findById(parentId).orElse(null));
        }
        return ResponseEntity.ok(folderRepository.save(folder));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFolder(@PathVariable UUID id, Authentication authentication) {
        Folder folder = folderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Carpeta no encontrada"));
        User requestUser = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        if (!folder.getUser().getId().equals(requestUser.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        folderService.deleteFolder(id);
        return ResponseEntity.noContent().build();
    }
}