package com.city.garbage.repo;

import com.city.garbage.domain.TaskStatus;
import com.city.garbage.domain.TaskType;
import com.city.garbage.domain.WorkTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkTaskRepository extends JpaRepository<WorkTask, Long> {
    Optional<WorkTask> findByDedupeKeyAndStatusNot(String dedupeKey, TaskStatus status);
    List<WorkTask> findByStatusNotOrderByPriorityDescCreatedAtAsc(TaskStatus status);
    List<WorkTask> findByAssigneeRoleAndStatusNotOrderByPriorityDescCreatedAtAsc(String role, TaskStatus status);
    List<WorkTask> findByAssigneeRoleAndTypeAndStatusNotOrderByPriorityDescCreatedAtAsc(String role, TaskType type, TaskStatus status);
    List<WorkTask> findByCaseNo(String caseNo);
    List<WorkTask> findByRectNo(String rectNo);
    long countByCaseNoAndStatus(String caseNo, TaskStatus status);
    long countByCaseNoAndStatusIn(String caseNo, List<TaskStatus> statuses);
}
