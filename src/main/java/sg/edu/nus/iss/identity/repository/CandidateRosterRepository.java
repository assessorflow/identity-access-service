package sg.edu.nus.iss.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import sg.edu.nus.iss.identity.entity.CandidateRoster;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateRosterRepository extends JpaRepository<CandidateRoster, UUID> {

    List<CandidateRoster> findAllByAssessorIdOrderByCreatedAtDesc(UUID assessorId);

    boolean existsByAssessorIdAndEmail(UUID assessorId, String email);

    Optional<CandidateRoster> findByIdAndAssessorId(UUID id, UUID assessorId);
}