package sg.edu.nus.iss.identity.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sg.edu.nus.iss.identity.dto.request.AddRosterRequest;
import sg.edu.nus.iss.identity.dto.response.RosterResponse;
import sg.edu.nus.iss.identity.entity.CandidateRoster;
import sg.edu.nus.iss.identity.exception.ServiceException;
import sg.edu.nus.iss.identity.repository.CandidateRosterRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateRosterServiceTest {

    @Mock private CandidateRosterRepository rosterRepository;

    @InjectMocks
    private CandidateRosterService rosterService;

    private final UUID assessorId = UUID.randomUUID();

    @Test
    void listRoster_returnsEntries() {
        CandidateRoster entry = CandidateRoster.builder()
                .id(UUID.randomUUID())
                .assessorId(assessorId)
                .name("Alice")
                .email("alice@example.com")
                .createdAt(Instant.now())
                .build();

        when(rosterRepository.findAllByAssessorIdOrderByCreatedAtDesc(assessorId))
                .thenReturn(List.of(entry));

        List<RosterResponse> result = rosterService.listRoster(assessorId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Alice");
        assertThat(result.get(0).getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void addToRoster_success() {
        AddRosterRequest request = new AddRosterRequest();
        request.setName("Bob");
        request.setEmail("bob@example.com");

        CandidateRoster saved = CandidateRoster.builder()
                .id(UUID.randomUUID())
                .assessorId(assessorId)
                .name("Bob")
                .email("bob@example.com")
                .createdAt(Instant.now())
                .build();

        when(rosterRepository.existsByAssessorIdAndEmail(assessorId, "bob@example.com")).thenReturn(false);
        when(rosterRepository.save(any(CandidateRoster.class))).thenReturn(saved);

        RosterResponse response = rosterService.addToRoster(assessorId, request);

        assertThat(response.getName()).isEqualTo("Bob");
        assertThat(response.getEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void addToRoster_duplicateEmail_throwsConflict() {
        AddRosterRequest request = new AddRosterRequest();
        request.setName("Bob");
        request.setEmail("bob@example.com");

        when(rosterRepository.existsByAssessorIdAndEmail(assessorId, "bob@example.com")).thenReturn(true);

        assertThatThrownBy(() -> rosterService.addToRoster(assessorId, request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("Email already exists");
    }

    @Test
    void removeFromRoster_success() {
        UUID rosterId = UUID.randomUUID();
        CandidateRoster entry = CandidateRoster.builder()
                .id(rosterId)
                .assessorId(assessorId)
                .build();

        when(rosterRepository.findByIdAndAssessorId(rosterId, assessorId)).thenReturn(Optional.of(entry));

        rosterService.removeFromRoster(assessorId, rosterId);

        verify(rosterRepository).delete(entry);
    }

    @Test
    void removeFromRoster_notFound_throwsNotFound() {
        UUID rosterId = UUID.randomUUID();

        when(rosterRepository.findByIdAndAssessorId(rosterId, assessorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rosterService.removeFromRoster(assessorId, rosterId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("not found");
    }
}