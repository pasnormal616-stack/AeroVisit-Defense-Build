package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    // Newest first, for the admin list
    List<Announcement> findAllByOrderByAnnouncementDateDescIdDesc();

    // Newest first, for the public Announcement Board
    List<Announcement> findByActiveTrueOrderByAnnouncementDateDescIdDesc();
}
