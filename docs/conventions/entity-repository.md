# Entity / Repository 구현 패턴

## Entity 패턴

```java
@Entity
@Table(name = "challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 기본 생성자 — 외부 직접 생성 방지
@AllArgsConstructor
@Builder
public class Challenge extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChallengeStatus status;

    // 상태 변경 메서드는 엔티티 내부에 정의
    public void end() {
        this.status = ChallengeStatus.ENDED;
    }
}
```

**규칙 요약:**
- `@Setter` 금지 — 상태 변경은 의미 있는 메서드(`end()`, `activate()`)로 표현
- `@Builder` + `@NoArgsConstructor(PROTECTED)` 조합
- `extends BaseEntity` — `createdAt`, `updatedAt` 자동 관리

---

## 서버 시각 — 주입된 Clock에서만, 마이크로초로 (#213)

DB 시각 컬럼(PostgreSQL `TIMESTAMPTZ` · H2 `TIMESTAMP(6)`)은 **마이크로초**까지만 담는다. JVM 시계(Linux)는 **나노초**를 주므로,
그대로 쓰면 저장 직후 엔티티 값(`.740903532`)과 DB 재조회 값(`.740904`, 드라이버가 반올림)이 달라진다.
macOS 시계는 마이크로초라 로컬에서는 안 보이고 Linux CI에서만 드러난다.

| 규칙 | 예 |
|---|---|
| 서버 시각은 **주입된 `Clock`** 으로 만든다 | `LocalDateTime.now(clock)` — `LocalDateTime.now()` · `now(ZoneId)` · `Instant.now()` 금지 |
| `Clock`은 **`RoutinelyClocks`** (`common-core`)에서만 만든다 | 운영 빈 `RoutinelyClocks.system(ZoneId.of("Asia/Seoul"))` · 테스트 `RoutinelyClocks.micros(Clock.fixed(…))` |
| `Clock.tick(base, Duration.ofNanos(1_000))`을 쓰지 않는다 | JDK `TickClock.millis()`가 틱을 밀리초로 나눠(=0) `ArithmeticException`을 던진다. `RoutinelyClocks`는 `instant()`만 마이크로초로 버리고 `millis()`는 그대로 준다 |
| 엔티티 안에서 시각을 만들지 않는다 | 엔티티는 Clock을 주입받을 수 없다. `@PrePersist`에서 `now()`를 부르지 말고 **팩토리 인자로 받는다** — `ChallengeOutbox.create(…, LocalDateTime.now(clock))` |
| `created_at` · `updated_at` 두 컬럼이면 `BaseEntity`를 상속한다 | Auditing이 Clock에서 채운다(`ChallengeMemberSummary`) |

- **Auditing(`createdAt` · `updatedAt`)** — `JpaAuditingConfig`의 `DateTimeProvider`가 서비스 `Clock` 빈의 시각을 마이크로초로 쓴다.
  빈이 없으면 시스템 시계를 쓴다(chat · notification). **시간대는 JVM 기본**이다 — #213 이전의 `LocalDateTime.now()`와 같은 벽시계를 유지한다.
  서비스 Clock(Asia/Seoul)과 JVM 기본 시간대의 통일은 별도 작업이다
- **반올림이 아니라 버림** — 만드는 순간 버려 두면 드라이버(pgJDBC는 half-up) · 서버(PostgreSQL 리터럴은 half-even)가 반올림할 자리가 없다.
  반올림하면 `…59.9999995`가 다음 초 · 다음 날로 넘어간다
- **테스트** — `Clock.fixed(…)`를 그대로 넣으면 마이크로초 절단을 건너뛴다. 정밀도를 볼 때는 나노초 원천을 `RoutinelyClocks.micros(…)`에 넣고
  **기대값(`.740903`)을 직접** 비교한다(`ServerTimePrecision*Test`). 저장값 = 재조회값만 보면 밀리초까지 버린 구현도 통과한다
- **정적 검사** — 각 서비스 `ServerTimeArchitectureTest`가 `ServerTimeRules`(ArchUnit, `common-core` testFixtures)로 위 두 규칙을 확인한다.
  대상 밖: `LocalDate.now(…)`(날짜라 정밀도 문제 없음), `java.util.Date`(JWT `issuedAt` — 밀리초 · DB에 저장 안 함)

---

## Repository — 쿼리 복잡도별 전략

### 1. Spring Data JPA 메서드명 — 단순 조건 조회

```java
public interface ChallengeRepository extends JpaRepository<Challenge, Long> {
    List<Challenge> findByStatus(ChallengeStatus status);
    Optional<Challenge> findByIdAndStatus(Long id, ChallengeStatus status);
}
```

### 2. @Query — 중간 복잡도 (JOIN 1~2개, 집계)

```java
public interface ChallengeRepository extends JpaRepository<Challenge, Long>,
        ChallengeRepositoryCustom {

    @Query("SELECT c FROM Challenge c WHERE c.status = :status AND c.endDate >= :today")
    List<Challenge> findActiveChallenges(@Param("status") ChallengeStatus status,
                                         @Param("today") LocalDate today);
}
```

### 3. QueryDSL — 복잡한 동적 쿼리 (다중 조건, 페이지네이션)

```java
@Repository
@RequiredArgsConstructor
public class ChallengeRepositoryImpl implements ChallengeRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Challenge> searchChallenges(ChallengeSearchCondition cond, Pageable pageable) {
        List<Challenge> content = queryFactory
                .selectFrom(challenge)
                .where(
                    statusEq(cond.getStatus()),
                    titleContains(cond.getKeyword())
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        long total = queryFactory.select(challenge.count()).from(challenge)
                .where(statusEq(cond.getStatus()), titleContains(cond.getKeyword()))
                .fetchOne();

        return new PageImpl<>(content, pageable, total);
    }

    private BooleanExpression statusEq(ChallengeStatus status) {
        return status != null ? challenge.status.eq(status) : null;
    }

    private BooleanExpression titleContains(String keyword) {
        return keyword != null ? challenge.title.containsIgnoreCase(keyword) : null;
    }
}
```

**기준:**
- 단순 조회 → JPA 메서드명
- JOIN / 집계 → `@Query`
- 동적 조건 / 페이지네이션 / 서브쿼리 → QueryDSL
