package sg.edu.nus.iss.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.nus.iss.identity.dto.request.AddRosterRequest;
import sg.edu.nus.iss.identity.dto.response.RosterResponse;
import sg.edu.nus.iss.identity.entity.CandidateRoster;
import sg.edu.nus.iss.identity.exception.ServiceException;
import sg.edu.nus.iss.identity.repository.CandidateRosterRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CandidateRosterService {

    private final CandidateRosterRepository rosterRepository;

    @Transactional(readOnly = true)
    public List<RosterResponse> listRoster(UUID assessorId) {
        return rosterRepository.findAllByAssessorIdOrderByCreatedAtDesc(assessorId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RosterResponse addToRoster(UUID assessorId, AddRosterRequest request) {
        if (rosterRepository.existsByAssessorIdAndEmail(assessorId, request.getEmail())) {
            throw ServiceException.conflict("Email already exists in this assessor's roster");
        }

        CandidateRoster entry = CandidateRoster.builder()
                .assessorId(assessorId)
                .name(request.getName())
                .email(request.getEmail())
                .build();
        entry = rosterRepository.save(entry);
        return toResponse(entry);
    }

    @Transactional
    public void removeFromRoster(UUID assessorId, UUID rosterId) {
        CandidateRoster entry = rosterRepository.findByIdAndAssessorId(rosterId, assessorId)
                .orElseThrow(() -> ServiceException.notFound("Roster entry not found or not owned by this assessor"));
        rosterRepository.delete(entry);
    }

    private RosterResponse toResponse(CandidateRoster entry) {
        return RosterResponse.builder()
                .id(entry.getId())
                .name(entry.getName())
                .email(entry.getEmail())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}
