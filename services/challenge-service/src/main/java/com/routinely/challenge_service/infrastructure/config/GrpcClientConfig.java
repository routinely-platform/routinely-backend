package com.routinely.challenge_service.infrastructure.config;

import com.routinely.proto.routine.RoutineGrpcServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    RoutineGrpcServiceGrpc.RoutineGrpcServiceBlockingStub routineServiceStub(GrpcChannelFactory channels) {
        return RoutineGrpcServiceGrpc.newBlockingStub(channels.createChannel("routine-service"));
    }
}
