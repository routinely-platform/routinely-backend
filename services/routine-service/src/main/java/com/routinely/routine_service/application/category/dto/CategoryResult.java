package com.routinely.routine_service.application.category.dto;

import com.routinely.routine_service.domain.category.Category;

public record CategoryResult(
        String code,
        String name,
        String icon,
        int displayOrder
) {

    public static CategoryResult from(Category category) {
        return new CategoryResult(
                category.getCode(),
                category.getName(),
                category.getIcon(),
                category.getDisplayOrder()
        );
    }
}
