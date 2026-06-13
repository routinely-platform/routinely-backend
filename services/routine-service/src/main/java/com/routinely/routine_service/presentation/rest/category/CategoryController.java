package com.routinely.routine_service.presentation.rest.category;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.response.ApiResponse;
import com.routinely.routine_service.application.category.CategoryService;
import com.routinely.routine_service.presentation.rest.category.dto.response.CategoryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> listCategories(
            @RequestHeader(HeaderConstants.USER_ID) Long userId) {

        List<CategoryResponse> response = categoryService.listActive()
                .stream()
                .map(CategoryResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("카테고리 목록이 조회되었습니다.", response));
    }
}
