package com.example.demo.service;


import com.example.demo.dto.request.AuthenticationRequest;
import com.example.demo.dto.request.ForgotPasswordRequest;
import com.example.demo.dto.request.RegisterRequest;
import com.example.demo.dto.request.ResetPasswordRequest;
import com.example.demo.dto.response.AuthenticationResponse;
import com.example.demo.entity.PasswordResetToken;
import com.example.demo.entity.User;
import com.example.demo.enums.Role;
import com.example.demo.exception.BaseException;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.TooManyRequestsException;
import com.example.demo.repository.PasswordResetTokenRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtService;
import com.example.demo.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_RESET_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailService emailService;

    public AuthenticationResponse register(RegisterRequest registerRequest) {
        if (userRepository.findByEmail(registerRequest.getEmail()).isPresent()) {
            throw new BusinessException("Email đã được sử dụng");
        }
        if (userRepository.existsByPhone(registerRequest.getPhone())) {
            throw new BusinessException("Số điện thoại đã được sử dụng");
        }

        User user = User.builder()
                .fullName(registerRequest.getFullName())
                .email(registerRequest.getEmail())
                .phone(registerRequest.getPhone())
                .password(passwordEncoder.encode(registerRequest.getPassword()))
                .role(Role.USER)
                .build();

        userRepository.save(user);

        UserPrincipal userPrincipal = UserPrincipal.create(user);

        String jwtToken = jwtService.generateToken(userPrincipal);
        return AuthenticationResponse.builder()
                .token(jwtToken)
                .build();
    }

    public AuthenticationResponse authenticate(AuthenticationRequest request) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        var user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException(request.getEmail()));

        var userPrincipal = UserPrincipal.create(user);

        var jwtToken = jwtService.generateToken(userPrincipal);

        return AuthenticationResponse.builder()
                .token(jwtToken)
                .build();
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.getEmail();
        if (!userRepository.existsByEmail(email)) {
            // Same response as for a real account, so the endpoint cannot be used to probe for e-mails.
            log.info("Forgot-password requested for unknown email");
            return;
        }

        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));

        // Delete old token if exists, or fetch it
        PasswordResetToken token = passwordResetTokenRepository.findByEmail(email)
                .orElse(new PasswordResetToken());

        token.setEmail(email);
        token.setToken(code);
        token.setExpiryDate(LocalDateTime.now().plusMinutes(5));
        token.setFailedAttempts(0);

        passwordResetTokenRepository.save(token);

        // Send email
        emailService.sendPasswordResetEmail(email, code);
    }

    /**
     * Rejected attempts must stay counted, hence no rollback for business exceptions.
     */
    @Transactional(noRollbackFor = BaseException.class)
    public void resetPassword(ResetPasswordRequest request) {
        String email = request.getEmail();
        String code = request.getCode();

        PasswordResetToken token = passwordResetTokenRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("Mã xác thực không chính xác hoặc không hợp lệ"));

        if (token.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new BusinessException("Mã xác thực đã hết hạn");
        }

        if (passwordResetTokenRepository.consumeAttempt(email, MAX_RESET_ATTEMPTS) == 0) {
            throw new TooManyRequestsException("Bạn đã nhập sai mã quá nhiều lần, vui lòng yêu cầu mã mới", null);
        }

        if (code == null || !MessageDigest.isEqual(
                token.getToken().getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("Mã xác thực không chính xác hoặc không hợp lệ");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("Người dùng không tồn tại"));

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Clean up reset token
        passwordResetTokenRepository.delete(token);
    }
}
