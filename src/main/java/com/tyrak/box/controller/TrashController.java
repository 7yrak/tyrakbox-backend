package com.tyrak.box.controller;

import com.tyrak.box.model.User;
import com.tyrak.box.repository.UserRepository;
import com.tyrak.box.service.TrashService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/trash")
public class TrashController {

    private final TrashService trashService;
    private final UserRepository userRepository;

    public TrashController(TrashService trashService, UserRepository userRepository) {
        this.trashService = trashService;
        this.userRepository = userRepository;
    }

    @GetMapping("/content")
    public ResponseEntity<Map<String, Object>> getTrashContent(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        
        Map<String, Object> content = trashService.getTrashContent(user);
        return ResponseEntity.ok(content);
    }

    @DeleteMapping("/empty")
    public ResponseEntity<?> emptyTrash(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        
        trashService.emptyTrash(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/restore/{id}")
    public ResponseEntity<?> restoreItem(@PathVariable UUID id, @RequestParam String type, Authentication authentication) {
        trashService.restoreItem(id, type);
        return ResponseEntity.ok().build();
    }
}
