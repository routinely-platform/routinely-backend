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
