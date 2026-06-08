package com.tyrak.box.controller;

import com.tyrak.box.service.FileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/chunk")
public class ChunkUploadController {

    private final FileService fileService;

    public ChunkUploadController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadChunk(
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam("chunkNumber") int chunkNumber,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("identifier") String identifier) throws IOException {
        
        fileService.saveChunk(chunk, chunkNumber, identifier);

        if (chunkNumber == totalChunks - 1) {
            // Lógica para ensamblar el archivo (se implementará después)
        }

        return ResponseEntity.ok().build();
    }
}
