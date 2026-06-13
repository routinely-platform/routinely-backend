package com.routinely.routine_service.presentation.grpc;

import com.routinely.proto.routine.CategoryItem;
import com.routinely.proto.routine.ListCategoriesRequest;
import com.routinely.proto.routine.ListCategoriesResponse;
import com.routinely.proto.routine.RoutineGrpcServiceGrpc;
import com.routinely.routine_service.application.category.CategoryService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;

@Slf4j
@GrpcService
public class RoutineGrpcService extends RoutineGrpcServiceGrpc.RoutineGrpcServiceImplBase {

    private final CategoryService categoryService;

    public RoutineGrpcService(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Override
    public void listCategories(ListCategoriesRequest request, StreamObserver<ListCategoriesResponse> responseObserver) {
        try {
            List<CategoryItem> items = categoryService.listActive().stream()
                    .map(r -> CategoryItem.newBuilder()
                            .setCode(r.code())
                            .setName(r.name())
                            .setIcon(r.icon())
                            .setDisplayOrder(r.displayOrder())
                            .build())
                    .toList();

            responseObserver.onNext(ListCategoriesResponse.newBuilder()
                    .addAllCategories(items)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("[gRPC] ListCategories 처리 중 오류 발생", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("내부 오류가 발생했습니다.")
                    .asRuntimeException());
        }
    }
}
