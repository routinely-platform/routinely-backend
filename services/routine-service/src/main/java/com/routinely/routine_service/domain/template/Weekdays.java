package com.routinely.routine_service.domain.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 요일 집합 ↔ 비트마스크 변환 유틸. {@code days_of_week}(SMALLINT)는 bit0=월 … bit6=일 비트마스크로 저장된다.
 * 예: 월·수·금 = {@code 0b0010101 = 21}.
 *
 * <p>API에서는 사람이 읽기 쉬운 코드(MON~SUN) 리스트로 주고받고, 저장/도메인에서는 비트마스크를 쓴다.
 */
public final class Weekdays {

    /** 인덱스 = 비트 위치. MON=bit0 … SUN=bit6. */
    private static final List<String> CODES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    /** 전체 요일(매일) 비트마스크. */
    public static final short ALL = 0b1111111;

    private Weekdays() {
    }

    public static boolean isValidCode(String code) {
        return CODES.contains(code);
    }

    /**
     * 요일 코드 집합을 비트마스크로 변환한다. 중복은 무시되며, 유효하지 않은 코드는 예외를 던진다.
     */
    public static short toBitmask(Collection<String> codes) {
        short mask = 0;
        for (String code : codes) {
            int index = CODES.indexOf(code);
            if (index < 0) {
                throw new IllegalArgumentException("유효하지 않은 요일 코드: " + code);
            }
            mask |= (short) (1 << index);
        }
        return mask;
    }

    /**
     * 비트마스크를 요일 코드 리스트(월→일 순)로 변환한다. NULL이면 빈 리스트를 반환한다.
     */
    public static List<String> toCodes(Short mask) {
        List<String> result = new ArrayList<>();
        if (mask == null) {
            return result;
        }
        for (int i = 0; i < CODES.size(); i++) {
            if ((mask & (1 << i)) != 0) {
                result.add(CODES.get(i));
            }
        }
        return result;
    }
}
