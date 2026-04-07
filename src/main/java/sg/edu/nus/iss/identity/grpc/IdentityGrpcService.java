package sg.edu.nus.iss.identity.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import sg.edu.nus.iss.identity.entity.User;
import sg.edu.nus.iss.identity.grpc.proto.IdentityServiceGrpc;
import sg.edu.nus.iss.identity.grpc.proto.ValidateTokenRequest;
import sg.edu.nus.iss.identity.grpc.proto.ValidateTokenResponse;
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
    public void validateToken(ValidateTokenRequest request, StreamObserver<ValidateTokenResponse> responseObserver) {
        try {
            if (!jwtService.isTokenValid(request.getAccessToken())) {
                responseObserver.onNext(ValidateTokenResponse.newBuilder()
                        .setValid(false)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            UUID userId = jwtService.extractUserId(request.getAccessToken());
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !user.getIsActive()) {
                responseObserver.onNext(ValidateTokenResponse.newBuilder()
                        .setValid(false)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            responseObserver.onNext(ValidateTokenResponse.newBuilder()
                    .setValid(true)
                    .setUserId(user.getId().toString())
                    .setRole(user.getRole())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error validating token", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during token validation")
                    .asRuntimeException());
        }
    }
}
