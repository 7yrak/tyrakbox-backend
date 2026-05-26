package com.tyrak.box.service;

import com.tyrak.box.model.File;
import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FileRepository;
import com.tyrak.box.repository.FolderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final FileRepository fileRepository;

    public FolderService(FolderRepository folderRepository, FileRepository fileRepository) {
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
    }

    @Transactional
    public void deleteFolder(UUID folderId) {
        folderRepository.findById(folderId).ifPresent(this::recursivelyDelete);
    }

    private void recursivelyDelete(Folder folder) {
        folder.setIsDeleted(true);
        folderRepository.save(folder);

        List<File> files = fileRepository.findByFolderAndIsDeletedFalse(folder.getId());
        for (File file : files) {
            file.setIsDeleted(true);
            fileRepository.save(file);
        }

        List<Folder> subFolders = folderRepository.findByParentAndIsDeletedFalse(folder);
        for (Folder subFolder : subFolders) {
            recursivelyDelete(subFolder);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized Folder findOrCreate(String name, User user, UUID parentId) {
        Optional<Folder> existingFolder = parentId == null ?
                folderRepository.findByNameAndUser_IdAndParentIsNull(name, user.getId()) :
                folderRepository.findByNameAndUser_IdAndParent_Id(name, user.getId(), parentId);

        if (existingFolder.isPresent()) {
            return existingFolder.get();
        } else {
            Folder parent = parentId != null ? folderRepository.findById(parentId).orElse(null) : null;
            Folder newFolder = new Folder();
            newFolder.setName(name);
            newFolder.setUser(user);
            newFolder.setParent(parent);
            newFolder.setIsDeleted(false);
            return folderRepository.save(newFolder);
        }
    }
}