package com.routinely.routine_service.presentation.rest.execution.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.routine_service.presentation.rest.execution.dto.response.ExecutionCompleteResponse;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import lombok.*;
public final class ExecutionDto {
    private ExecutionDto() {}
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class BulkCompleteRequest {
        @NotNull(message = "수행 날짜는 필수입니다.")
        private LocalDate scheduledDate;
        @NotNull(message = "루틴 목록은 필수입니다.")
        private List<@NotNull(message = "루틴 ID는 필수입니다.") @Positive(message = "루틴 ID는 양수여야 합니다.") Long> routineIds;
        @JsonIgnore
        @AssertTrue(message = "루틴 ID는 중복될 수 없습니다.")
        public boolean isUnique() {
            return routineIds == null || routineIds.stream().distinct().count() == routineIds.size();
        }
    }
    @Getter
    @Builder
    public static class BulkCompleteResponse {
        private int createdCount;
        private List<ExecutionCompleteResponse> executions;
    }
}
