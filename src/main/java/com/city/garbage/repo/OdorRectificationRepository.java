package com.city.garbage.repo;

import com.city.garbage.domain.OdorRectification;
import com.city.garbage.domain.OdorRectificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OdorRectificationRepository extends JpaRepository<OdorRectification, Long> {
    Optional<OdorRectification> findByRectNo(String rectNo);
    List<OdorRectification> findByRoomCodeAndStatusIn(String roomCode, List<OdorRectificationStatus> statuses);
    List<OdorRectification> findByStatus(OdorRectificationStatus status);
    List<OdorRectification> findByRoomCodeOrderByCreatedAtDesc(String roomCode);
    List<OdorRectification> findAllByOrderByCreatedAtDesc();
}
