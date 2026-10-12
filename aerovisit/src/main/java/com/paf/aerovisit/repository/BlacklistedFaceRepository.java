package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.BlacklistedFace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlacklistedFaceRepository extends JpaRepository<BlacklistedFace, Long> {
}
