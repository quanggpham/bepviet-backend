package com.example.demo.controller;

import jakarta.validation.Valid;
import com.example.demo.dto.request.CheckoutRequest;
import com.example.demo.dto.response.ApiResponse;
import com.example.demo.dto.response.OrderResponse;
import com.example.demo.dto.response.PageResponse;
import com.example.demo.enums.OrderStatus;
import com.example.demo.security.UserPrincipal;
import com.example.demo.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> checkout(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Valid @RequestBody CheckoutRequest checkoutRequest
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<OrderResponse>builder()
                        .message("Dat don hang thanh cong")
                        .status(HttpStatus.CREATED.value())
                        .data(orderService.createOrder(userPrincipal.getId(), checkoutRequest))
                        .build()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        return ResponseEntity.status(HttpStatus.OK).body(
                ApiResponse.<OrderResponse>builder()
                        .message("Xem chi tiet don hang thanh cong")
                        .data(orderService.getById(id, userPrincipal.getId()))
                        .status(HttpStatus.OK.value())
                        .build()
        );
    }

    @PutMapping("/cancel/{id}")
    public ResponseEntity<ApiResponse<Void>> cancelOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        orderService.cancelOrder(id, userPrincipal.getId());
        return ResponseEntity.status(HttpStatus.OK).body(
                ApiResponse.<Void>builder()
                        .message("Huy don hang thanh cong")
                        .status(HttpStatus.OK.value())
                        .build()
        );
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> getOrdersByUserId(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 10, page = 0) Pageable pageable
    ) {
        return ResponseEntity.status(HttpStatus.OK).body(
                ApiResponse.<PageResponse<OrderResponse>>builder()
                        .message("Xem danh sach don hang thanh cong")
                        .status(HttpStatus.OK.value())
                        .data(orderService.getAllByUserId(userPrincipal.getId(), pageable, status))
                        .build()
        );
    }
}
