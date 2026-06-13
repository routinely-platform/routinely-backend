package com.routinely.routine_service.presentation.grpc;

import com.routinely.proto.routine.CategoryItem;
import com.routinely.proto.routine.ListCategoriesRequest;
import com.routinely.proto.routine.ListCategoriesResponse;
import com.routinely.routine_service.application.category.CategoryService;
import com.routinely.routine_service.application.category.dto.CategoryResult;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@DisplayName("RoutineGrpcService")
class RoutineGrpcServiceTest {

    private CategoryService categoryService;
    private RoutineGrpcService grpcService;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        grpcService = new RoutineGrpcService(categoryService);
    }

    @Test
    @DisplayName("활성 카테고리 목록을 proto 응답으로 변환해 완료한다")
    void listCategories_returnsActiveCategories() {
        when(categoryService.listActive()).thenReturn(List.of(
                new CategoryResult("EXERCISE", "운동", "🏃", 1),
                new CategoryResult("READING", "독서", "📚", 2)
        ));
        StreamObserver<ListCategoriesResponse> observer = mockObserver();

        grpcService.listCategories(ListCategoriesRequest.newBuilder().build(), observer);

        ArgumentCaptor<ListCategoriesResponse> captor = ArgumentCaptor.forClass(ListCategoriesResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        verify(observer, never()).onError(any());

        assertThat(captor.getValue().getCategoriesList())
                .extracting(CategoryItem::getCode)
                .containsExactly("EXERCISE", "READING");
        assertThat(captor.getValue().getCategoriesList())
                .extracting(CategoryItem::getName)
                .containsExactly("운동", "독서");
        assertThat(captor.getValue().getCategoriesList())
                .extracting(CategoryItem::getIcon)
                .containsExactly("🏃", "📚");
        assertThat(captor.getValue().getCategoriesList())
                .extracting(CategoryItem::getDisplayOrder)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("조회 결과가 비어도 빈 목록 응답으로 완료한다")
    void listCategories_whenEmpty_respondsEmptyList() {
        when(categoryService.listActive()).thenReturn(List.of());
        StreamObserver<ListCategoriesResponse> observer = mockObserver();

        grpcService.listCategories(ListCategoriesRequest.newBuilder().build(), observer);

        ArgumentCaptor<ListCategoriesResponse> captor = ArgumentCaptor.forClass(ListCategoriesResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        verify(observer, never()).onError(any());

        assertThat(captor.getValue().getCategoriesList()).isEmpty();
    }

    @Test
    @DisplayName("예상치 못한 예외이면 INTERNAL 오류를 전달한다")
    void listCategories_whenUnexpectedException_respondsInternal() {
        when(categoryService.listActive()).thenThrow(new IllegalStateException("DB 연결 실패"));
        StreamObserver<ListCategoriesResponse> observer = mockObserver();

        grpcService.listCategories(ListCategoriesRequest.newBuilder().build(), observer);

        StatusRuntimeException error = capturedError(observer);
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    @SuppressWarnings("unchecked")
    private StreamObserver<ListCategoriesResponse> mockObserver() {
        return mock(StreamObserver.class);
    }

    private StatusRuntimeException capturedError(StreamObserver<ListCategoriesResponse> observer) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(StatusRuntimeException.class);
        return (StatusRuntimeException) captor.getValue();
    }
}
