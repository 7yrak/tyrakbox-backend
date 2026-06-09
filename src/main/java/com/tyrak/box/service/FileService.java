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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;
    private final LocalFolderSyncService localFolderSyncService;
    private final Path rootLocation;
    private final Map<UUID, Folder> folderByIdCache = new ConcurrentHashMap<>();
    private final Map<String, Folder> folderPathCache = new ConcurrentHashMap<>();

    public FileService(FileRepository fileRepository, FolderRepository folderRepository, LocalFolderSyncService localFolderSyncService, @Value("${storage.location:uploads}") String storageLocation) {
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.localFolderSyncService = localFolderSyncService;
        this.rootLocation = Paths.get(storageLocation);
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo inicializar el almacenamiento", e);
        }
    }

    @Transactional
    public synchronized File uploadFile(MultipartFile multipartFile, String relativePath, User user, Folder folder) throws IOException {
        String sourcePath = relativePath != null && !relativePath.isBlank()
                ? relativePath
                : multipartFile.getOriginalFilename();
        String fileName = sourcePath;
        Folder targetFolder = folder;

        if (sourcePath != null && (sourcePath.contains("/") || sourcePath.contains("\\"))) {
            String normalizedPath = sourcePath.replace("\\", "/");
            String[] parts = normalizedPath.split("/");
            fileName = parts[parts.length - 1];

            Folder currentParent = folder;
            for (int i = 0; i < parts.length - 1; i++) {
                currentParent = findOrCreateFolder(parts[i], user, currentParent);
            }
            targetFolder = currentParent;
        }

        String syncPath = buildSyncPath(fileName, targetFolder);
        Path destination = resolvePhysicalPath(user.getId(), targetFolder, fileName);
        java.util.Optional<File> existing = fileRepository.findLatestBySyncPathAndUserId(syncPath, user.getId());
        if (existing.isPresent() && Boolean.FALSE.equals(existing.get().getIsDeleted()) && Files.exists(destination)) {
            return existing.get();
        }

        File file = new File();
        file.setName(fileName);
        file.setMimeType(multipartFile.getContentType());
        file.setSizeBytes(multipartFile.getSize());
        file.setUser(user);
        file.setFolder(targetFolder);
        file.setIsDeleted(false);
        file.setSyncPath(syncPath);

        File savedFile = fileRepository.save(file);
        try {
            Files.createDirectories(destination.getParent());
            Files.copy(multipartFile.getInputStream(), destination);
        } catch (IOException e) {
            log.error("Error escribiendo archivo físico userId={} fileName={} destination={}", user.getId(), fileName, destination, e);
            throw e;
        }
        
        return savedFile;
    }

    private Folder findOrCreateFolder(String name, User user, Folder parent) {
        UUID parentId = (parent != null) ? parent.getId() : null;
        String cacheKey = buildFolderCacheKey(user.getId(), parentId, name);

        Folder cached = folderPathCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        Optional<Folder> existing = (parentId == null)
            ? folderRepository.findByNameAndUser_IdAndParentIsNull(name, user.getId())
            : folderRepository.findByNameAndUser_IdAndParent_Id(name, user.getId(), parentId);

        Folder resolved = existing.orElseGet(() -> {
            Folder newFolder = new Folder();
            newFolder.setName(name);
            newFolder.setUser(user);
            newFolder.setParent(parent);
            newFolder.setIsDeleted(false);
            return folderRepository.save(newFolder);
        });

        folderPathCache.put(cacheKey, resolved);
        folderByIdCache.put(resolved.getId(), resolved);
        return resolved;
    }

    public Folder resolveFolderForUser(UUID folderId, UUID userId) {
        if (folderId == null) {
            return null;
        }

        Folder cached = folderByIdCache.get(folderId);
        if (cached != null && cached.getUser() != null && userId.equals(cached.getUser().getId())) {
            return cached;
        }

        Folder resolved = folderRepository.findByIdAndUserId(folderId, userId)
                .orElseThrow(() -> new RuntimeException("Carpeta no encontrada"));
        folderByIdCache.put(folderId, resolved);
        return resolved;
    }

    public void saveChunk(MultipartFile chunk, int chunkNumber, String identifier) throws IOException {
        Path chunkDir = rootLocation.resolve("chunks").resolve(identifier);
        Files.createDirectories(chunkDir);
        Path chunkFile = chunkDir.resolve(String.valueOf(chunkNumber));
        Files.write(chunkFile, chunk.getBytes(), StandardOpenOption.CREATE);
    }

    @Transactional(readOnly = true)
    public File getFileMetadata(UUID fileId, User user) {
        return fileRepository.findByIdAndUserId(fileId, user.getId())
                .orElseThrow(() -> new RuntimeException("Archivo no encontrado en la base de datos"));
    }

    public Resource loadFileAsResource(UUID fileId) {
        try {
            File file = fileRepository.findById(fileId)
                    .orElseThrow(() -> new RuntimeException("Archivo no encontrado"));
            Path filePath = resolvePhysicalPath(file.getUser().getId(), file.getFolder(), file.getName()).normalize();
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
    public void deleteFile(UUID fileId, User user) {
        fileRepository.findByIdAndUserId(fileId, user.getId()).ifPresent(file -> {
            file.setIsDeleted(true);
            fileRepository.save(file);
            localFolderSyncService.mirrorDeleteFromApp(file);
        });
    }

    private Path resolveUserStoragePath(UUID userId) {
        return rootLocation.resolve(userId.toString());
    }

    private Path resolvePhysicalPath(UUID userId, Folder folder, String fileName) {
        Path userRoot = resolveUserStoragePath(userId);
        return userRoot.resolve(buildFolderRelativePath(folder)).resolve(fileName);
    }

    private String buildSyncPath(String fileName, Folder folder) {
        String prefix = buildFolderPath(folder);
        return prefix + fileName;
    }

    private String buildFolderPath(Folder folder) {
        if (folder == null) return "";
        String parent = buildFolderPath(folder.getParent());
        return parent + folder.getName() + "/";
    }

    private Path buildFolderRelativePath(Folder folder) {
        if (folder == null) {
            return Path.of("");
        }

        java.util.LinkedList<String> segments = new java.util.LinkedList<>();
        Folder current = folder;
        while (current != null) {
            segments.addFirst(current.getName());
            current = current.getParent();
        }
        return Path.of("", segments.toArray(new String[0]));
    }

    private String buildFolderCacheKey(UUID userId, UUID parentId, String name) {
        return userId + ":" + (parentId != null ? parentId : "root") + ":" + name;
    }
}
