package com.city.garbage.repo;

import com.city.garbage.domain.OdorReading;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface OdorReadingRepository extends JpaRepository<OdorReading, Long> {
    long countByRoomCodeAndExceededAndRecordedAtBetween(String roomCode, boolean exceeded,
                                                        OffsetDateTime from, OffsetDateTime to);
    List<OdorReading> findByRoomCodeAndRecordedAtBetweenOrderByRecordedAtAsc(String roomCode,
                                                                             OffsetDateTime from, OffsetDateTime to);
}
