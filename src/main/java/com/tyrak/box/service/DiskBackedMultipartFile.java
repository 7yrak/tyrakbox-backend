package com.tyrak.box.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

class DiskBackedMultipartFile implements MultipartFile {

    private final String originalFilename;
    private final Path path;

    DiskBackedMultipartFile(String originalFilename, Path path) {
        this.originalFilename = originalFilename;
        this.path = path;
    }

    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return "application/octet-stream"; }
    @Override public boolean isEmpty() { return false; }
    @Override public long getSize() { try { return Files.size(path); } catch (IOException e) { return 0L; } }
    @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
    @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
    @Override public void transferTo(Path dest) throws IOException { Files.copy(path, dest); }
    @Override public void transferTo(java.io.File dest) throws IOException { Files.copy(path, dest.toPath()); }
}
