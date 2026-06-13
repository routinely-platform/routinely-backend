package com.routinely.routine_service.presentation.rest.category.dto.response;

import com.routinely.routine_service.application.category.dto.CategoryResult;

public record CategoryResponse(
        String code,
        String name,
        String icon,
        int displayOrder
) {

    public static CategoryResponse from(CategoryResult result) {
        return new CategoryResponse(
                result.code(),
                result.name(),
                result.icon(),
                result.displayOrder()
        );
    }
}
