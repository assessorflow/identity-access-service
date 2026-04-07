package sg.edu.nus.iss.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.nus.iss.identity.dto.request.UpdateUserRequest;
import sg.edu.nus.iss.identity.dto.response.UserResponse;
import sg.edu.nus.iss.identity.entity.User;
import sg.edu.nus.iss.identity.exception.ServiceException;
import sg.edu.nus.iss.identity.repository.UserRepository;
import sg.edu.nus.iss.identity.security.UserContextCache;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserContextCache userContextCache;

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID userId) {
        User user = findUserOrThrow(userId);
        return toUserResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(User user) {
        return toUserResponse(user);
    }

    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = findUserOrThrow(userId);

        if (request.getFullName() != null) {
            user.setFullName(request.getFullName());
        }
        if (request.getPassword() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        user = userRepository.save(user);
        userContextCache.cacheUserContext(user);
        return toUserResponse(user);
    }

    @Transactional
    public void deactivateUser(UUID userId) {
        User user = findUserOrThrow(userId);
        user.setIsActive(false);
        userRepository.save(user);
        userContextCache.evictUserContext(userId);
    }

    @Transactional
    public void activateUser(UUID userId) {
        User user = findUserOrThrow(userId);
        user.setIsActive(true);
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public boolean userExists(UUID userId) {
        return userRepository.existsById(userId);
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ServiceException.notFound("User not found"));
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
