package com.tyrak.box.service;

import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FolderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class FolderSyncService {

    private final FolderRepository folderRepository;

    public FolderSyncService(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    // REQUIRES_NEW asegura que la carpeta se guarde en la DB inmediatamente, 
    // antes de que el archivo termine de subirse.
    // synchronized evita que dos hilos entren aquí al mismo tiempo.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized Folder getOrCreateFolder(String name, User user, Folder parent) {
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
        
        return folderRepository.saveAndFlush(newFolder);
    }
}