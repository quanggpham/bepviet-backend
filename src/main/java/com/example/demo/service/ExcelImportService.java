package com.example.demo.service;

import com.example.demo.config.CacheConfig;
import com.example.demo.entity.Category;
import com.example.demo.entity.Product;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.CategoryRepository;
import com.example.demo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExcelImportService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    /**
     * Import sản phẩm từ file Excel (.xlsx)
     * Cấu trúc cột: name | description | price | stockQuantity | imageUrl | categoryName
     */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PUBLIC_PRODUCTS, allEntries = true)
    public ExcelImportResult importProducts(MultipartFile file) throws IOException {
        validateFile(file);

        List<String> errors = new ArrayList<>();
        List<Product> successProducts = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();

            // Bắt đầu từ row 1 (bỏ qua header row 0)
            for (int i = 1; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) continue;

                totalRows++;
                try {
                    Product product = parseRow(row, i + 1);
                    successProducts.add(product);
                } catch (Exception e) {
                    errors.add("Dòng " + (i + 1) + ": " + e.getMessage());
                }
            }
        }

        // Lưu tất cả sản phẩm hợp lệ
        List<Product> savedProducts = productRepository.saveAll(successProducts);

        return new ExcelImportResult(
                totalRows,
                savedProducts.size(),
                errors.size(),
                errors
        );
    }

    /**
     * Tạo file Excel mẫu để admin tải về
     */
    public byte[] generateTemplate() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Sản phẩm");

            // Style cho header
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 12);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            // Header row
            String[] headers = {"Tên sản phẩm (*)", "Mô tả", "Giá (*)", "Số lượng tồn kho", "Link ảnh", "Tên danh mục (*)"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000);
            }

            // Dòng mẫu
            Row sampleRow = sheet.createRow(1);
            sampleRow.createCell(0).setCellValue("Phở bò Hà Nội");
            sampleRow.createCell(1).setCellValue("Phở bò truyền thống Hà Nội, nước dùng đậm đà");
            sampleRow.createCell(2).setCellValue(55000);
            sampleRow.createCell(3).setCellValue(100);
            sampleRow.createCell(4).setCellValue("https://example.com/pho.jpg");
            sampleRow.createCell(5).setCellValue("Món chính");

            Row sampleRow2 = sheet.createRow(2);
            sampleRow2.createCell(0).setCellValue("Bún chả Hà Nội");
            sampleRow2.createCell(1).setCellValue("Bún chả thơm ngon với nước mắm pha đặc biệt");
            sampleRow2.createCell(2).setCellValue(45000);
            sampleRow2.createCell(3).setCellValue(50);
            sampleRow2.createCell(4).setCellValue("");
            sampleRow2.createCell(5).setCellValue("Món chính");

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File không được để trống");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("Chỉ hỗ trợ file Excel định dạng .xlsx");
        }
        // Giới hạn 5MB
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("File không được vượt quá 5MB");
        }
    }

    private Product parseRow(Row row, int rowNum) {
        String name = getStringCell(row, 0);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên sản phẩm không được để trống");
        }

        String description = getStringCell(row, 1);

        BigDecimal price = getNumericCell(row, 2);
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Giá sản phẩm phải >= 0");
        }

        Long stockQuantity = null;
        BigDecimal stockVal = getNumericCell(row, 3);
        if (stockVal != null) {
            stockQuantity = stockVal.longValue();
        }

        String imageUrl = getStringCell(row, 4);

        String categoryName = getStringCell(row, 5);
        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("Tên danh mục không được để trống");
        }

        Category category = categoryRepository.findByName(categoryName.trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category", "name", categoryName.trim()));

        return Product.builder()
                .name(name.trim())
                .description(description)
                .price(price)
                .stockQuantity(stockQuantity)
                .imageUrl(imageUrl)
                .category(category)
                .isActive(true)
                .build();
    }

    private String getStringCell(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BLANK -> null;
            default -> cell.toString().trim();
        };
    }

    private BigDecimal getNumericCell(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;

        return switch (cell.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> {
                String val = cell.getStringCellValue().trim();
                if (val.isEmpty()) yield null;
                try {
                    yield new BigDecimal(val);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Giá trị không hợp lệ: " + val);
                }
            }
            case BLANK -> null;
            default -> throw new IllegalArgumentException("Kiểu dữ liệu không hỗ trợ ở cột " + (col + 1));
        };
    }

    private boolean isRowEmpty(Row row) {
        for (int i = 0; i < 6; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    public record ExcelImportResult(
            int totalRows,
            int successCount,
            int errorCount,
            List<String> errors
    ) {}
}
