package com.tyrak.box.repository;

import com.tyrak.box.model.File;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<File, UUID> {

    @Query(value = "SELECT * FROM files WHERE user_id = CAST(:userId AS uuid) AND folder_id IS NULL AND is_deleted = false", nativeQuery = true)
    List<File> findRootFilesForUser(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM files WHERE user_id = CAST(:userId AS uuid) AND folder_id = CAST(:folderId AS uuid) AND is_deleted = false", nativeQuery = true)
    List<File> findFilesByFolderId(@Param("userId") UUID userId, @Param("folderId") UUID folderId);

    @Query(value = "SELECT * FROM files WHERE user_id = CAST(:userId AS uuid) AND is_deleted = true", nativeQuery = true)
    List<File> findDeletedFilesForUser(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM files WHERE folder_id = CAST(:folderId AS uuid) AND is_deleted = false", nativeQuery = true)
    List<File> findByFolderAndIsDeletedFalse(@Param("folderId") UUID folderId);

    @Query(value = "SELECT * FROM files WHERE folder_id = CAST(:folderId AS uuid) AND is_deleted = true", nativeQuery = true)
    List<File> findByFolderAndIsDeletedTrue(@Param("folderId") UUID folderId);
}