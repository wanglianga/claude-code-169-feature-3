package com.city.garbage.repo;

import com.city.garbage.domain.Complaint;
import com.city.garbage.domain.ComplaintStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    List<Complaint> findByCommunityIdOrderByCreatedAtDesc(Long communityId);
    List<Complaint> findByCommunityIdAndCreatedAtBetween(Long communityId, OffsetDateTime from, OffsetDateTime to);
    long countByCommunityIdAndCreatedAtBetween(Long communityId, OffsetDateTime from, OffsetDateTime to);
    long countByCommunityIdAndStatusAndCreatedAtBetween(Long communityId, ComplaintStatus status,
                                                        OffsetDateTime from, OffsetDateTime to);
    long countByRoomCodeAndCreatedAtBetween(String roomCode, OffsetDateTime from, OffsetDateTime to);

    @Query("select coalesce(sum(case when c.status = com.city.garbage.domain.ComplaintStatus.RESOLVED then 1 else 0 end),0) " +
            "from Complaint c where c.communityId = :cid and c.createdAt between :from and :to")
    long resolvedCount(@Param("cid") Long communityId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
