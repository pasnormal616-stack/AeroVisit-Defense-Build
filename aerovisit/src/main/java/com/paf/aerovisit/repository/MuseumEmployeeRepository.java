package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.MuseumEmployee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MuseumEmployeeRepository extends JpaRepository<MuseumEmployee, Long> {
}