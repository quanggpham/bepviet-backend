package com.example.demo.controller;

import jakarta.validation.Valid;
import com.example.demo.dto.request.ProductCreationRequest;
import com.example.demo.dto.request.ProductSearchRequest;
import com.example.demo.dto.request.ProductUpdateRequest;
import com.example.demo.dto.response.ApiResponse;
import com.example.demo.dto.response.PageResponse;
import com.example.demo.dto.response.ProductResponse;
import com.example.demo.security.UserPrincipal;
import com.example.demo.service.ExcelImportService;
import com.example.demo.service.ProductLikeService;
import com.example.demo.service.ProductLikeService.ProductLikeSummary;
import com.example.demo.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;
    private final ProductLikeService productLikeService;
    private final ExcelImportService excelImportService;

    @PostMapping("/{id}/like")
    public ResponseEntity<ApiResponse<Map<String, Object>>> likeProduct(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        ProductLikeSummary summary = productLikeService.likeProduct(id, currentUser.getId());
        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .status(HttpStatus.OK.value())
                .message("Da thich san pham")
                .data(Map.of("liked", summary.liked(), "likeCount", summary.likeCount()))
                .build();
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}/like")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlikeProduct(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        ProductLikeSummary summary = productLikeService.unlikeProduct(id, currentUser.getId());
        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .status(HttpStatus.OK.value())
                .message("Da bo thich san pham")
                .data(Map.of("liked", summary.liked(), "likeCount", summary.likeCount()))
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/liked")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getLikedProducts(
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        List<ProductResponse> data = productLikeService.getLikedProducts(currentUser.getId());
        ApiResponse<List<ProductResponse>> response = ApiResponse.<List<ProductResponse>>builder()
                .status(HttpStatus.OK.value())
                .message("Lay danh sach san pham yeu thich thanh cong")
                .data(data)
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> create(@Valid @RequestBody ProductCreationRequest request) {
        ApiResponse<ProductResponse> response = ApiResponse.<ProductResponse>builder()
                .status(201)
                .message("Tao moi product thanh cong")
                .data(productService.create(request))
                .build();
        return ResponseEntity.status(201).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        ApiResponse<ProductResponse> response = ApiResponse.<ProductResponse>builder()
                .status(HttpStatus.OK.value())
                .data(productService.update(id, request))
                .message("Cap nhat san pham thanh cong")
                .build();
        return ResponseEntity.status(HttpStatus.OK.value()).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> delete(@PathVariable Long id) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponse.<ProductResponse>builder()
                        .status(HttpStatus.NO_CONTENT.value())
                        .data(productService.delete(id))
                        .message("Xoa thanh cong")
                        .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> findById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponse.<ProductResponse>builder()
                        .status(HttpStatus.OK.value())
                        .message("Lay thong tin san pham thanh cong")
                        .data(productService.findById(id, currentUser != null ? currentUser.getId() : null))
                        .build());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> search(
            ProductSearchRequest request,
            @PageableDefault(page = 0, size = 10) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.<PageResponse<ProductResponse>>builder()
                        .status(HttpStatus.OK.value())
                        .message("Tim kiem thanh cong")
                        .data(productService.search(request, pageable,
                                currentUser != null ? currentUser.getId() : null, isAdmin(currentUser)))
                        .build());
    }

    private static boolean isAdmin(UserPrincipal user) {
        return user != null && user.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    @PostMapping("/import-excel")
    public ResponseEntity<ApiResponse<ExcelImportService.ExcelImportResult>> importFromExcel(
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        ExcelImportService.ExcelImportResult result = excelImportService.importProducts(file);
        String message = String.format("Import hoàn tất: %d/%d sản phẩm thành công",
                result.successCount(), result.totalRows());
        return ResponseEntity.ok(
                ApiResponse.<ExcelImportService.ExcelImportResult>builder()
                        .status(HttpStatus.OK.value())
                        .message(message)
                        .data(result)
                        .build()
        );
    }

    @GetMapping("/import-excel/template")
    public ResponseEntity<byte[]> downloadTemplate() throws IOException {
        byte[] template = excelImportService.generateTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", "product_import_template.xlsx");
        return ResponseEntity.ok().headers(headers).body(template);
    }
}
