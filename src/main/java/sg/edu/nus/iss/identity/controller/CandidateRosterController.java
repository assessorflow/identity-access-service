package sg.edu.nus.iss.identity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sg.edu.nus.iss.identity.dto.request.AddRosterRequest;
import sg.edu.nus.iss.identity.dto.response.RosterResponse;
import sg.edu.nus.iss.identity.entity.User;
import sg.edu.nus.iss.identity.service.CandidateRosterService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roster")
@RequiredArgsConstructor
public class CandidateRosterController {

    private final CandidateRosterService rosterService;

    @GetMapping
    public ResponseEntity<Map<String, List<RosterResponse>>> listRoster(@AuthenticationPrincipal User user) {
        List<RosterResponse> items = rosterService.listRoster(user.getId());
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PostMapping
    public ResponseEntity<RosterResponse> addToRoster(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AddRosterRequest request) {
        RosterResponse response = rosterService.addToRoster(user.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{rosterId}")
    public ResponseEntity<Void> removeFromRoster(
            @AuthenticationPrincipal User user,
            @PathVariable UUID rosterId) {
        rosterService.removeFromRoster(user.getId(), rosterId);
        return ResponseEntity.noContent().build();
    }
}
