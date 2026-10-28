package com.routinely.core.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Weekdays")
class WeekdaysTest {

    @Test
    @DisplayName("월·수·금은 비트마스크 21이다 — bit0=월 … bit6=일 (두 서비스가 공유하는 저장 규칙)")
    void toBitmask_monWedFri_returns21() {
        assertThat(Weekdays.toBitmask(List.of("MON", "WED", "FRI"))).isEqualTo((short) 21);
    }

    @Test
    @DisplayName("비트마스크를 월→일 순 코드 리스트로 되돌린다")
    void toCodes_bitmask_returnsOrderedCodes() {
        assertThat(Weekdays.toCodes((short) 0b1000101)).containsExactly("MON", "WED", "SUN");
        assertThat(Weekdays.toCodes(null)).isEmpty();
    }

    @Test
    @DisplayName("contains는 DayOfWeek의 월(1)~일(7)을 bit0~bit6에 대응시킨다")
    void contains_dayOfWeek_matchesBitPosition() {
        assertThat(Weekdays.contains((short) 21, DayOfWeek.WEDNESDAY)).isTrue();
        assertThat(Weekdays.contains((short) 21, DayOfWeek.TUESDAY)).isFalse();
        assertThat(Weekdays.contains(null, DayOfWeek.MONDAY)).isFalse();
    }

    @Test
    @DisplayName("유효하지 않은 요일 코드는 예외를 던진다")
    void toBitmask_invalidCode_throwsException() {
        assertThatThrownBy(() -> Weekdays.toBitmask(List.of("MON", "FUNDAY")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
