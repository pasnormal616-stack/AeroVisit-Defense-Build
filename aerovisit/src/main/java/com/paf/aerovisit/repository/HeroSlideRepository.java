package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.HeroSlide;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HeroSlideRepository extends JpaRepository<HeroSlide, Long> {

    // Slideshow order: as arranged on Website Content (then oldest first)
    List<HeroSlide> findAllByOrderByDisplayOrderAscIdAsc();
}
