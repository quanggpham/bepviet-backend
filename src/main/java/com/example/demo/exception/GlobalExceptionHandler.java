package com.example.demo.exception;

import com.example.demo.dto.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException; // Đảm bảo import đúng AccessDeniedException của Security
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    private String getPath(WebRequest request) {
        return request.getDescription(false).replace("uri=", "");
    }


    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFoundException(ResourceNotFoundException ex, WebRequest request) {
        logger.warn("ResourceNotFound: Message= {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .status(HttpStatus.NOT_FOUND.value())
                .message(ex.getMessage())
                .path(getPath(request))
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }


    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex, WebRequest request) {
        logger.warn("AccessDenied: Message= {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .status(HttpStatus.FORBIDDEN.value())
                .message("Bạn không có quyền truy cập tài nguyên này")
                .path(getPath(request))
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }


    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BaseException ex, WebRequest request) {
        logger.warn("BusinessException: Class= {} | Message= {}", ex.getClass().getName(), ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .message(ex.getMessage())
                .path(getPath(request))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }


    /**
     * Written directly to the response so a 429 still reaches clients that only accept
     * text/event-stream (the streaming chat endpoint), which content negotiation would refuse.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public void handleTooManyRequests(TooManyRequestsException ex, WebRequest request, HttpServletResponse response) throws IOException {
        logger.warn("TooManyRequests: Path= {} | Message= {}", getPath(request), ex.getMessage());

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .message(ex.getMessage())
                .path(getPath(request))
                .build();

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        if (ex.getRetryAfterSeconds() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }


    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException ex, WebRequest request) {
        logger.warn("AuthenticationException: Message= {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .message("Email hoặc mật khẩu không chính xác")
                .path(getPath(request))
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex, WebRequest request) {
        logger.warn("MethodArgumentNotValidException: Message= {}", ex.getMessage());

        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .message("Dữ liệu đầu vào không hợp lệ")
                .path(getPath(request))
                .errors(errors) // Map danh sách lỗi vào trường errors của bạn
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleSystemException(Exception ex, WebRequest request) {
        logger.error("SystemException: ", ex);

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .message("Đã xảy ra lỗi hệ thống, vui lòng thử lại sau")
                .path(getPath(request))
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}