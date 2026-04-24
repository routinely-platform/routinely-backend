# 테스트 구현 패턴

## 테스트 계층

```
단위 테스트 (Unit Test)       → Service 레이어, 도메인 로직
슬라이스 테스트 (Slice Test)   → Repository, Controller 레이어 개별 검증
통합 테스트 (Integration Test) → 전체 흐름 (실제 DB 사용)
```

---

## 1. 단위 테스트 — Service 레이어

Mockito로 외부 의존성을 Mock 처리. 빠른 피드백, 순수 비즈니스 로직 검증.

```java
@ExtendWith(MockitoExtension.class)
class ChallengeServiceImplTest {

    @InjectMocks
    private ChallengeServiceImpl challengeService;

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private ChallengeOutboxRepository outboxRepository;

    @Test
    @DisplayName("챌린지 참여 성공")
    void join_success() {
        // given
        Long challengeId = 1L;
        Long userId = 10L;
        Challenge challenge = createChallenge(challengeId);
        given(challengeRepository.findById(challengeId)).willReturn(Optional.of(challenge));

        // when
        challengeService.join(challengeId, userId);

        // then
        verify(outboxRepository, times(1)).save(any(ChallengeOutbox.class));
    }

    @Test
    @DisplayName("존재하지 않는 챌린지 참여 시 예외 발생")
    void join_challengeNotFound() {
        // given
        given(challengeRepository.findById(anyLong())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> challengeService.join(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("챌린지를 찾을 수 없습니다");
    }

    private Challenge createChallenge(Long id) {
        return Challenge.builder()
                .title("테스트 챌린지")
                .status(ChallengeStatus.WAITING)
                .build();
    }
}
```

---

## 2. 슬라이스 테스트 — Repository

`@DataJpaTest` + H2 인메모리 DB. JPA 쿼리 정확성만 검증.

```java
@DataJpaTest
@ActiveProfiles("test")
class ChallengeRepositoryTest {

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("상태로 챌린지 목록 조회")
    void findByStatus() {
        // given
        challengeRepository.save(Challenge.builder().title("A").status(ChallengeStatus.ACTIVE).build());
        challengeRepository.save(Challenge.builder().title("B").status(ChallengeStatus.ENDED).build());
        em.flush();
        em.clear();

        // when
        List<Challenge> result = challengeRepository.findByStatus(ChallengeStatus.ACTIVE);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("A");
    }
}
```

---

## 3. 슬라이스 테스트 — Controller

`@WebMvcTest` + MockMvc. HTTP 요청/응답 형식, 상태코드, 유효성 검사 검증.

```java
@WebMvcTest(ChallengeController.class)
class ChallengeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChallengeService challengeService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("챌린지 생성 성공 — 201 반환")
    void create_success() throws Exception {
        // given
        ChallengeDto.CreateRequest request = new ChallengeDto.CreateRequest("30일 챌린지", LocalDate.now());
        ChallengeDto.CreateResponse response = ChallengeDto.CreateResponse.builder()
                .challengeId(1L).title("30일 챌린지").build();
        given(challengeService.create(any(), anyLong())).willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/challenges")
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.challengeId").value(1L));
    }

    @Test
    @DisplayName("제목 없이 챌린지 생성 시 400 반환")
    void create_titleBlank_400() throws Exception {
        ChallengeDto.CreateRequest request = new ChallengeDto.CreateRequest("", LocalDate.now());

        mockMvc.perform(post("/api/v1/challenges")
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
```

---

## 테스트 네이밍 규칙

```java
// 클래스명: 대상클래스 + Test
class ChallengeServiceImplTest { }
class ChallengeRepositoryTest { }
class ChallengeControllerTest { }

// 메서드명: 간결하게, @DisplayName 한글 필수
@Test
@DisplayName("챌린지 참여 성공")
void join_success() { }

@Test
@DisplayName("이미 참여한 챌린지 재참여 시 예외 발생")
void join_alreadyJoined_throwsException() { }
```

---

## @Profile 활용 패턴

```java
// local 프로파일에서 GatewayAuthFilter 비활성화 (ADR-0019)
@Component
@Profile("!local")
public class GatewayAuthFilter extends OncePerRequestFilter { ... }

// prod 프로파일에서만 S3 실제 클라이언트 등록
@Bean
@Profile("prod")
public FileStorage s3FileStorage(AmazonS3 s3Client) {
    return new S3FileStorage(s3Client);
}

// local/dev 프로파일에서 로컬 파일 저장소 사용
@Bean
@Profile("!prod")
public FileStorage localFileStorage() {
    return new LocalFileStorage();
}
```

```yaml
# 프로파일별 application.yml
# application-local.yml    — 로컬 IntelliJ 직접 실행
# application-prod.yml     — 배포 환경

# application-local.yml 예시
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/routinely_challenge
gateway:
  secret: ${GATEWAY_SECRET:local-secret}   # 기본값 설정으로 환경변수 없이 기동 가능
```
