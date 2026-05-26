package com.tyrak.box.repository;

import com.tyrak.box.model.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FolderRepository extends JpaRepository<Folder, UUID> {
    
    @Query(value = "SELECT * FROM folders WHERE user_id = CAST(:userId AS uuid) AND parent_id IS NULL AND is_deleted = false", nativeQuery = true)
    List<Folder> findRootFoldersForUser(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM folders WHERE user_id = CAST(:userId AS uuid) AND parent_id = CAST(:parentId AS uuid) AND is_deleted = false", nativeQuery = true)
    List<Folder> findSubFoldersByParentId(@Param("userId") UUID userId, @Param("parentId") UUID parentId);

    @Query(value = "SELECT * FROM folders WHERE user_id = CAST(:userId AS uuid) AND is_deleted = true", nativeQuery = true)
    List<Folder> findDeletedFoldersForUser(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM folders WHERE parent_id = CAST(:parentId AS uuid) AND is_deleted = false", nativeQuery = true)
    List<Folder> findSubFoldersByParentIdAndNotDeleted(@Param("parentId") UUID parentId);

    @Query(value = "SELECT * FROM folders WHERE parent_id = CAST(:parentId AS uuid) AND is_deleted = true", nativeQuery = true)
    List<Folder> findSubFoldersByParentIdAndDeleted(@Param("parentId") UUID parentId);

    @Query(value = "SELECT * FROM folders WHERE name = :name AND user_id = CAST(:userId AS uuid) AND parent_id IS NULL AND is_deleted = false", nativeQuery = true)
    Optional<Folder> findByNameAndUser_IdAndParentIsNull(@Param("name") String name, @Param("userId") UUID userId);
    
    @Query(value = "SELECT * FROM folders WHERE name = :name AND user_id = CAST(:userId AS uuid) AND parent_id = CAST(:parentId AS uuid) AND is_deleted = false", nativeQuery = true)
    Optional<Folder> findByNameAndUser_IdAndParent_Id(@Param("name") String name, @Param("userId") UUID userId, @Param("parentId") UUID parentId);
}