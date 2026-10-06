package com.city.garbage.repo;

import com.city.garbage.domain.RectificationReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RectificationReportRepository extends JpaRepository<RectificationReport, Long> {
    List<RectificationReport> findByRectificationIdOrderByCreatedAtAsc(Long rectificationId);
}
