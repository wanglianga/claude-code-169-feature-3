package com.city.garbage.repo;

import com.city.garbage.domain.Alert;
import com.city.garbage.domain.AlertStatus;
import com.city.garbage.domain.AlertType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    Optional<Alert> findByDedupeKeyAndStatus(String dedupeKey, AlertStatus status);
    Optional<Alert> findByDedupeKeyAndStatusIn(String dedupeKey, List<AlertStatus> statuses);
    List<Alert> findByDisposalIdAndStatusIn(Long disposalId, List<AlertStatus> statuses);
    boolean existsByDedupeKeyAndStatus(String dedupeKey, AlertStatus status);
    List<Alert> findByStatus(AlertStatus status);
    List<Alert> findByRoomCodeAndStatus(String roomCode, AlertStatus status);
    List<Alert> findByRoomCodeAndStatusIn(String roomCode, List<AlertStatus> statuses);
    List<Alert> findByCaseNo(String caseNo);
    List<Alert> findByTypeAndStatus(AlertType type, AlertStatus status);
    long countByRoomCodeAndTypeInAndCreatedAtBetween(String roomCode, List<AlertType> types,
                                                     OffsetDateTime from, OffsetDateTime to);
}
