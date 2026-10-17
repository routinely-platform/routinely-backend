package com.routinely.routine_service.application.challenge;

/**
 * challenge-service {@code GetChallengeContext} 결과. (#58)
 *
 * <p>proto 타입이 application 계층으로 새지 않도록 infrastructure의 gRPC 클라이언트가 이 값으로 바꿔 넘긴다.
 * 챌린지가 없으면({@code NOT_FOUND}) 장애가 아니라 "없음" 결과이므로 {@link #notFound()}로 표현한다.
 *
 * @param exists       챌린지가 존재하는지. false면 {@code status}는 null이다
 * @param status       챌린지 진행 상태
 * @param memberActive 요청한 사용자가 ACTIVE 멤버인지
 */
public record ChallengeContext(boolean exists, Status status, boolean memberActive) {

    public enum Status {
        WAITING, ACTIVE, ENDED
    }

    public static ChallengeContext of(Status status, boolean memberActive) {
        return new ChallengeContext(true, status, memberActive);
    }

    public static ChallengeContext notFound() {
        return new ChallengeContext(false, null, false);
    }
}
