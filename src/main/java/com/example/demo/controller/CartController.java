package com.example.demo.controller;

import jakarta.validation.Valid;
import com.example.demo.dto.request.AddToCartRequest;
import com.example.demo.dto.request.UpdateCartRequest;
import com.example.demo.dto.response.ApiResponse;
import com.example.demo.dto.response.CartResponse;
import com.example.demo.security.UserPrincipal;
import com.example.demo.service.CartService;
import com.example.demo.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carts")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;

    @PostMapping("/add")
    public ResponseEntity<ApiResponse<Void>> addToCart(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody AddToCartRequest request
            )
    {
        cartService.addToCart(currentUser.getId(), request);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponse.<Void>builder()
                        .status(HttpStatus.OK.value())
                        .message("Thêm vào giỏ hàng thành công!")
                        .build());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        return ResponseEntity.
                status(HttpStatus.OK)
                .body(ApiResponse.<CartResponse>builder()
                        .status(HttpStatus.OK.value())
                        .message("Get giỏ hàng thành công")
                        .data(cartService.getCart(currentUser.getId()))
                        .build());
    }

    @PutMapping("/item")
    public ResponseEntity<ApiResponse<Void>> updateQuantity(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody UpdateCartRequest request
    ) {
        cartService.updateQuantity(currentUser.getId(), request);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.<Void>builder()
                        .message("Cập nhật số lượng thành công")
                        .status(HttpStatus.OK.value())
                        .build());
    }

    @DeleteMapping("/item/{productId}")
    public ResponseEntity<ApiResponse<Void>> deleteItem(
            @AuthenticationPrincipal  UserPrincipal currentUser,
            @PathVariable Long productId

    ) {
        cartService.deleteItem(currentUser.getId(), productId);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.<Void>builder()
                        .status(HttpStatus.OK.value())
                        .message("Xóa sản phẩm thành công")
                        .build());
    }

    @DeleteMapping("/clear")
    public ResponseEntity<ApiResponse<Void>> clearCart(@AuthenticationPrincipal UserPrincipal currentUser) {
        cartService.clear(currentUser.getId());
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .status(HttpStatus.OK.value())
                        .message("Giỏ hàng đã được dọn sạch")
                        .build()
        );
    }

}
