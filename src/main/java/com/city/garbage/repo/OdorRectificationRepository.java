package com.city.garbage.repo;

import com.city.garbage.domain.OdorRectification;
import com.city.garbage.domain.RectificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface OdorRectificationRepository extends JpaRepository<OdorRectification, Long> {
    boolean existsByRoomCodeAndStatusIn(String roomCode, List<RectificationStatus> statuses);
    long countByRoomCode(String roomCode);
    List<OdorRectification> findByRoomCodeOrderByCreatedAtDesc(String roomCode);
    List<OdorRectification> findByStatusInOrderByCreatedAtDesc(List<RectificationStatus> statuses);
    List<OdorRectification> findAllByOrderByCreatedAtDesc();
    List<OdorRectification> findByStatusAndReviewAtBefore(RectificationStatus status, OffsetDateTime reviewAt);
}
