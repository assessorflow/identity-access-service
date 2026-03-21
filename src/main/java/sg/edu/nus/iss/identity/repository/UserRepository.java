package sg.edu.nus.iss.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import sg.edu.nus.iss.identity.entity.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
