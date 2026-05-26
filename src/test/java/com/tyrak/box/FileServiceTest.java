package com.tyrak.box.service;

import com.tyrak.box.model.File;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FileRepository;
import com.tyrak.box.repository.FolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private FolderRepository folderRepository;

    private FileService fileService;

    @TempDir
    Path tempDir;

    private User testUser;

    @BeforeEach
    void setUp() {
        fileService = new FileService(fileRepository, folderRepository, tempDir.toString());
        
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");
    }

    @Test
    void uploadFile_Success() throws IOException {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.txt", "text/plain", "Contenido de prueba".getBytes());

        when(fileRepository.save(any(File.class))).thenAnswer(i -> {
            File f = i.getArgument(0);
            f.setId(UUID.randomUUID());
            return f;
        });

        File result = fileService.uploadFile(mockFile, testUser, null);

        assertNotNull(result.getId());
        assertEquals("test.txt", result.getName());
        verify(fileRepository, times(1)).save(any(File.class));
    }
}