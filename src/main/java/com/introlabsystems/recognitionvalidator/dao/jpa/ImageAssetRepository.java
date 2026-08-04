package com.introlabsystems.recognitionvalidator.dao.jpa;

import com.introlabsystems.recognitionvalidator.model.entity.ImageAsset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, String> {

    @Modifying
    @Query("update ImageAsset asset set asset.fileAvailable = false where asset.id = :id")
    int markUnavailableById(@Param("id") String id);
}
