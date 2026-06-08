package com.tyrak.box.controller;

import com.tyrak.box.service.LocalFolderSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final LocalFolderSyncService localFolderSyncService;

    public SyncController(LocalFolderSyncService localFolderSyncService) {
        this.localFolderSyncService = localFolderSyncService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(localFolderSyncService.getStatus());
    }
}
