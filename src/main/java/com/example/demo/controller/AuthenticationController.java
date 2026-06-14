package com.example.demo.controller;

import com.example.demo.dto.request.AuthenticationRequest;
import com.example.demo.dto.request.RegisterRequest;
import com.example.demo.dto.request.ForgotPasswordRequest;
import com.example.demo.dto.request.ResetPasswordRequest;
import com.example.demo.dto.response.ApiResponse;
import com.example.demo.dto.response.AuthenticationResponse;
import com.example.demo.security.ClientIpResolver;
import com.example.demo.security.RequestRateLimits;
import com.example.demo.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthenticationController {
    private final AuthenticationService authenticationService;
    private final RequestRateLimits rateLimits;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<AuthenticationResponse>builder()
                        .status(HttpStatus.CREATED.value())
                        .message("Đăng ký thành công")
                        .data(authenticationService.register(request))
                        .build()
        );
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> authenticate(
            @Valid @RequestBody AuthenticationRequest request,
            HttpServletRequest httpRequest
    ) {
        String ip = clientIpResolver.resolve(httpRequest);
        rateLimits.checkLogin(ip, request.getEmail());

        AuthenticationResponse response;
        try {
            response = authenticationService.authenticate(request);
        } catch (AuthenticationException ex) {
            rateLimits.loginFailed(ip, request.getEmail());
            throw ex;
        }
        rateLimits.loginSucceeded(ip, request.getEmail());

        return ResponseEntity.ok(
                ApiResponse.<AuthenticationResponse>builder()
                        .status(HttpStatus.OK.value())
                        .message("Đăng nhập thành công")
                        .data(response)
                        .build()
        );
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        rateLimits.checkForgotPassword(clientIpResolver.resolve(httpRequest), request.getEmail());
        authenticationService.forgotPassword(request);
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .status(HttpStatus.OK.value())
                        .message("Nếu email tồn tại trong hệ thống, mã xác minh đã được gửi đến email đó")
                        .build()
        );
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        rateLimits.checkResetPassword(clientIpResolver.resolve(httpRequest));
        authenticationService.resetPassword(request);
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .status(HttpStatus.OK.value())
                        .message("Khôi phục mật khẩu thành công")
                        .build()
        );
    }
}
