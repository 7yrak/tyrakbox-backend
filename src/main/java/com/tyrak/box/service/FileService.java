package com.tyrak.box.service;

import com.tyrak.box.model.File;
import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FileRepository;
import com.tyrak.box.repository.FolderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

@Service
public class FileService {

    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;
    private final Path rootLocation;

    public FileService(FileRepository fileRepository, FolderRepository folderRepository, @Value("${storage.location:uploads}") String storageLocation) {
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.rootLocation = Paths.get(storageLocation);
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo inicializar el almacenamiento", e);
        }
    }

    // Usamos synchronized para asegurar que, si llegan peticiones paralelas, se procesen una tras otra
    @Transactional
    public synchronized File uploadFile(MultipartFile multipartFile, User user, Folder folder) throws IOException {
        
        String originalFilename = multipartFile.getOriginalFilename();
        String fileName = originalFilename;
        Folder targetFolder = folder;

        // Limpieza de ruta y creación de subcarpetas
        if (originalFilename != null && (originalFilename.contains("/") || originalFilename.contains("\\"))) {
            // Normalizamos los separadores a '/'
            String normalizedPath = originalFilename.replace("\\", "/");
            String[] parts = normalizedPath.split("/");
            
            fileName = parts[parts.length - 1]; // El último es el archivo
            
            // Construimos la estructura de carpetas
            Folder currentParent = folder;
            for (int i = 0; i < parts.length - 1; i++) {
                currentParent = findOrCreateFolder(parts[i], user, currentParent);
            }
            targetFolder = currentParent;
        }

        File file = new File();
        file.setName(fileName);
        file.setMimeType(multipartFile.getContentType());
        file.setSizeBytes(multipartFile.getSize());
        file.setUser(user);
        file.setFolder(targetFolder);
        file.setIsDeleted(false);

        File savedFile = fileRepository.save(file);
        
        // Guardamos el archivo físico
        Files.copy(multipartFile.getInputStream(), this.rootLocation.resolve(savedFile.getId().toString()));
        
        return savedFile;
    }

    private Folder findOrCreateFolder(String name, User user, Folder parent) {
        UUID parentId = (parent != null) ? parent.getId() : null;
        
        Optional<Folder> existing;
        if (parentId == null) {
            existing = folderRepository.findByNameAndUser_IdAndParentIsNull(name, user.getId());
        } else {
            existing = folderRepository.findByNameAndUser_IdAndParent_Id(name, user.getId(), parentId);
        }

        if (existing.isPresent()) {
            return existing.get();
        }

        Folder newFolder = new Folder();
        newFolder.setName(name);
        newFolder.setUser(user);
        newFolder.setParent(parent);
        newFolder.setIsDeleted(false);
        return folderRepository.save(newFolder);
    }

    @Transactional(readOnly = true)
    public File getFileMetadata(UUID fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("Archivo no encontrado en la base de datos"));
    }

    public Resource loadFileAsResource(UUID fileId) {
        try {
            Path filePath = this.rootLocation.resolve(fileId.toString()).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists()) {
                return resource;
            } else {
                throw new RuntimeException("Archivo no encontrado");
            }
        } catch (MalformedURLException ex) {
            throw new RuntimeException("Ruta malformada", ex);
        }
    }

    @Transactional
    public void deleteFile(UUID fileId) {
        fileRepository.findById(fileId).ifPresent(file -> {
            file.setIsDeleted(true);
            fileRepository.save(file);
        });
    }
}