package com.tyrak.box.repository;

import com.tyrak.box.model.UploadJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UploadJobRepository extends JpaRepository<UploadJob, UUID> {

    @Query("SELECT j FROM UploadJob j WHERE j.user.id = :userId ORDER BY j.createdAt DESC")
    List<UploadJob> findRecentByUserId(@Param("userId") UUID userId);

    @Query("SELECT j FROM UploadJob j WHERE j.user.id = :userId AND j.status IN ('PENDING','PROCESSING') ORDER BY j.createdAt DESC")
    List<UploadJob> findActiveByUserId(@Param("userId") UUID userId);
}
