package com.tyrak.box.service;

import com.tyrak.box.model.File;
import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FileRepository;
import com.tyrak.box.repository.FolderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TrashService {

    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;
    private final Path rootLocation;

    public TrashService(FileRepository fileRepository, FolderRepository folderRepository, @Value("${storage.location:uploads}") String storageLocation) {
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.rootLocation = Paths.get(storageLocation);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTrashContent(User user) {
        List<File> files = fileRepository.findDeletedFilesForUser(user.getId());
        // Corregido: Usar el método de Spring Data JPA
        List<Folder> folders = folderRepository.findByUserIdAndIsDeletedTrue(user.getId());

        Map<String, Object> content = new HashMap<>();
        content.put("files", files);
        content.put("folders", folders);
        return content;
    }

    @Transactional
    public void emptyTrash(User user) {
        List<File> filesToDelete = fileRepository.findDeletedFilesForUser(user.getId());
        List<Folder> foldersToDelete = folderRepository.findByUserIdAndIsDeletedTrue(user.getId());

        for (File file : filesToDelete) {
            try {
                Files.deleteIfExists(rootLocation.resolve(file.getId().toString()));
            } catch (IOException e) {
                System.err.println("No se pudo borrar el archivo físico: " + file.getId());
            }
        }

        fileRepository.deleteAllInBatch(filesToDelete);
        folderRepository.deleteAllInBatch(foldersToDelete);
    }

    @Transactional
    public void restoreItem(UUID id, String type) {
        if ("file".equals(type)) {
            fileRepository.findById(id).ifPresent(file -> {
                file.setIsDeleted(false);
                fileRepository.save(file);
            });
        } else if ("folder".equals(type)) {
            folderRepository.findById(id).ifPresent(this::recursivelyRestore);
        }
    }

    private void recursivelyRestore(Folder folder) {
        folder.setIsDeleted(false);
        folderRepository.save(folder);

        List<File> files = fileRepository.findByFolderAndIsDeletedTrue(folder.getId());
        for (File file : files) {
            file.setIsDeleted(false);
            fileRepository.save(file);
        }

        List<Folder> subFolders = folderRepository.findSubFoldersByParentIdAndDeleted(folder.getId());
        for (Folder subFolder : subFolders) {
            recursivelyRestore(subFolder);
        }
    }
}