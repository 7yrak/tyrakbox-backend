package com.tyrak.box.service;

import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FileRepository;
import com.tyrak.box.repository.FolderRepository;
import com.tyrak.box.service.LocalFolderSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FolderServiceTest {

    @Mock
    private FolderRepository folderRepository;

    @Mock
    private FileRepository fileRepository;

    @Mock
    private LocalFolderSyncService localFolderSyncService;

    @InjectMocks
    private FolderService folderService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");
    }

    @Test
    void findOrCreate_RootLevel_Success() {
        // Arrange
        when(folderRepository.findByNameAndUser_IdAndParentIsNull(anyString(), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(folderRepository.save(any(Folder.class))).thenAnswer(i -> {
            Folder f = i.getArgument(0);
            f.setId(UUID.randomUUID());
            return f;
        });

        // Act
        Folder result = folderService.findOrCreate("Documentos", testUser, null);

        // Assert
        assertNotNull(result.getId());
        assertEquals("Documentos", result.getName());
        assertEquals(testUser, result.getUser());
        assertNull(result.getParent());
        assertFalse(result.getIsDeleted());
        verify(folderRepository, times(1)).save(any(Folder.class));
    }

    @Test
    void findOrCreate_WithParent_Success() {
        // Arrange
        UUID parentId = UUID.randomUUID();
        Folder parentFolder = new Folder();
        parentFolder.setId(parentId);

        when(folderRepository.findById(parentId)).thenReturn(Optional.of(parentFolder));
        when(folderRepository.findByNameAndUser_IdAndParent_Id(anyString(), any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(folderRepository.save(any(Folder.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        Folder result = folderService.findOrCreate("Subcarpeta", testUser, parentId);

        // Assert
        assertEquals("Subcarpeta", result.getName());
        assertNotNull(result.getParent());
        assertEquals(parentId, result.getParent().getId());
        verify(folderRepository, times(1)).findById(parentId);
        verify(folderRepository, times(1)).save(any(Folder.class));
    }
}
