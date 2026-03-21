package sg.edu.nus.iss.identity.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import sg.edu.nus.iss.identity.entity.User;
import sg.edu.nus.iss.identity.grpc.proto.*;
import sg.edu.nus.iss.identity.repository.UserRepository;
import sg.edu.nus.iss.identity.service.JwtService;

import java.util.UUID;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class IdentityGrpcService extends IdentityServiceGrpc.IdentityServiceImplBase {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    public void validateToken(ValidateTokenRequest request, StreamObserver<UserContext> responseObserver) {
        try {
            if (!jwtService.isTokenValid(request.getToken())) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Invalid or expired token")
                        .asRuntimeException());
                return;
            }

            UUID userId = jwtService.extractUserId(request.getToken());
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !user.getIsActive()) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("User not found or inactive")
                        .asRuntimeException());
                return;
            }

            UserContext context = UserContext.newBuilder()
                    .setUserId(user.getId().toString())
                    .setEmail(user.getEmail())
                    .setFullName(user.getFullName())
                    .setRole(user.getRole())
                    .setIsActive(user.getIsActive())
                    .build();

            responseObserver.onNext(context);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error validating token", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during token validation")
                    .asRuntimeException());
        }
    }

    @Override
    public void getUser(GetUserRequest request, StreamObserver<UserResponse> responseObserver) {
        try {
            UUID userId = UUID.fromString(request.getUserId());
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("User not found")
                        .asRuntimeException());
                return;
            }

            UserResponse response = UserResponse.newBuilder()
                    .setUserId(user.getId().toString())
                    .setEmail(user.getEmail())
                    .setFullName(user.getFullName())
                    .setRole(user.getRole())
                    .setIsActive(user.getIsActive())
                    .setCreatedAt(user.getCreatedAt().toString())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid user ID format")
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("Error getting user", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error")
                    .asRuntimeException());
        }
    }

    @Override
    public void userExists(UserExistsRequest request, StreamObserver<UserExistsResponse> responseObserver) {
        try {
            UUID userId = UUID.fromString(request.getUserId());
            boolean exists = userRepository.existsById(userId);

            responseObserver.onNext(UserExistsResponse.newBuilder()
                    .setExists(exists)
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid user ID format")
                    .asRuntimeException());
        }
    }
}
