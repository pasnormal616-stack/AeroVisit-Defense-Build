package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.ExhibitPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExhibitPhotoRepository extends JpaRepository<ExhibitPhoto, Long> {

    // Slideshow order: oldest upload first
    List<ExhibitPhoto> findAllByOrderByUploadDateAscIdAsc();
}
