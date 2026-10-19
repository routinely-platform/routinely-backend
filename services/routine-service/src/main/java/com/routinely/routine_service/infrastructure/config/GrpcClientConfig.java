package com.routinely.routine_service.infrastructure.config;

import com.routinely.proto.challenge.ChallengeGrpcServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    ChallengeGrpcServiceGrpc.ChallengeGrpcServiceBlockingStub challengeServiceStub(GrpcChannelFactory channels) {
        return ChallengeGrpcServiceGrpc.newBlockingStub(channels.createChannel("challenge-service"));
    }
}
