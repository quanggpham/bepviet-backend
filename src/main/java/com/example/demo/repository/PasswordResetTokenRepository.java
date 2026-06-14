package com.example.demo.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.example.demo.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByEmail(String email);
    Optional<PasswordResetToken> findByEmailAndToken(String email, String token);
    void deleteByEmail(String email);

    /**
     * Spends one verification attempt on the current code. Returns 0 once the budget is used up,
     * so parallel guesses are counted one by one under the row lock.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PasswordResetToken t SET t.failedAttempts = COALESCE(t.failedAttempts, 0) + 1 " +
            "WHERE t.email = :email AND COALESCE(t.failedAttempts, 0) < :maxAttempts")
    int consumeAttempt(@Param("email") String email, @Param("maxAttempts") int maxAttempts);
}
