package com.pharmquest.pharmquest.domain.medicine.service;

import com.pharmquest.pharmquest.domain.medicine.data.Medicine;
import com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory;
import com.pharmquest.pharmquest.domain.medicine.repository.MedRepository;
import com.pharmquest.pharmquest.domain.medicine.repository.MedicineRepository;
import com.pharmquest.pharmquest.support.DbTestSupport;
import com.pharmquest.pharmquest.support.MySqlContainerTestConfig;
import com.pharmquest.pharmquest.support.RequiresDocker;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-010: saveOtherMedicines / saveMedicinesByCategory를 실제 서비스(Spring 트랜잭션 프록시 포함) + 실제 MedicineConverter +
 * 실제 JPA + Testcontainers MySQL로 실행한다.
 *
 * <h3>실제인 것 / 대체한 것</h3>
 * <ul>
 *   <li>실제: MedicineServiceImpl(@Transactional, TransactionTemplate), MedicineConverter(변환·ID 추출·Executor), MedRepository, Hibernate, MySQL 컨테이너</li>
 *   <li>Mock(외부 경계만): {@link MedicineRepository}(openFDA/DailyMed 호출), {@link TranslationService}(Google 번역).
 *       FDA 응답 JSON은 테스트가 만든 값이며 실제 openFDA 응답 형태를 보장하지 않는다.</li>
 * </ul>
 *
 * <p>테스트 메서드에 @Transactional을 쓰지 않는다. 커밋 여부는 별도 JDBC 연결({@link DbTestSupport})로 확인한다.
 * 스키마는 create-drop으로 생성한 테스트용이며 운영 DDL과 같다는 보장이 없다.
 */
@RequiresDocker
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlContainerTestConfig.class)
class MedicineSaveJpaIntegrationTests {

    private static final String QUERY = "q";

    @MockBean
    private MedicineRepository medicineRepository;   // 외부 FDA/DailyMed 호출 차단
    @MockBean
    private TranslationService translationService;   // 외부 번역 호출 차단

    @Autowired
    private MedicineServiceImpl service;
    @Autowired
    private MedRepository medRepository;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private DbTestSupport db;
    private Statistics stats;

    @BeforeEach
    void setUp() {
        db = new DbTestSupport(dataSource);
        db.deleteAllMedicines();
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        when(translationService.translateText(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(medicineRepository.fetchImageData(anyString())).thenAnswer(inv ->
                "{\"data\":{\"media\":[{\"url\":\"http://localhost.invalid/" + inv.getArgument(0) + ".png\"}]}}");
    }

    // ---------------------------------------------------------------- 입력 도우미

    /** 유효한 FDA 결과 1건. splJson이 null이면 spl_set_id 필드를 넣지 않는다. */
    private static String item(String splJson, String brandName) {
        String spl = splJson == null ? "" : ",\"spl_set_id\":" + splJson;
        return "{\"openfda\":{\"brand_name\":[\"" + brandName + "\"],\"generic_name\":[\"Generic\"],"
                + "\"substance_name\":[\"Substance\"],\"route\":[\"ORAL\"]" + spl + "},"
                + "\"active_ingredient\":[\"Ingredient\"],\"purpose\":[\"Pain\"],"
                + "\"indications_and_usage\":[\"Use\"],\"dosage_and_administration\":[\"Dose\"],"
                + "\"warnings\":[\"Warn\"]}";
    }

    private static String validItem(String id) {
        return item("[\"" + id + "\"]", "Brand-" + id);
    }

    private void fdaReturns(String... items) {
        String body = "{\"results\":[" + String.join(",", items) + "]}";
        when(medicineRepository.fetchMedicineData(anyString(), anyInt())).thenReturn(body);
    }

    private List<String> committedSplSetIds() {
        return medRepository.findAll().stream().map(Medicine::getSplSetId).sorted().collect(Collectors.toList());
    }

    private List<String> imageFetchedIds() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(medicineRepository, org.mockito.Mockito.atLeast(0)).fetchImageData(captor.capture());
        return captor.getAllValues();
    }

    // ---------------------------------------------------------------- saveOtherMedicines (@Transactional, 건별 save)

    @Test
    @DisplayName("[S1] 정상: 유효 ID 3개, limit 3 -> 3행이 커밋되고 DB의 splSetId가 JSON의 ID와 같다")
    void s1_other_normal() {
        fdaReturns(validItem("A"), validItem("B"), validItem("C"));

        List<Medicine> result = service.saveOtherMedicines(QUERY, 3);

        assertThat(result).hasSize(3);
        assertThat(db.committedMedicineCount()).isEqualTo(3);
        assertThat(committedSplSetIds()).containsExactly("A", "B", "C");
        assertThat(medRepository.findBySplSetId("A")).get()
                .satisfies(m -> {
                    assertThat(m.getCategory()).isEqualTo(MedicineCategory.OTHER);
                    assertThat(m.getCountry()).isEqualTo("USA");
                    assertThat(m.getImgUrl()).isEqualTo("http://localhost.invalid/A.png");
                });
    }

    @Test
    @DisplayName("[S2] 경계: 응답 안에 같은 ID 반복(A,A,B), limit 2 -> A,B 한 행씩, A의 변환(이미지 조회)은 1회")
    void s2_other_duplicateIdsInsideResponse() {
        fdaReturns(validItem("A"), validItem("A"), validItem("B"));

        service.saveOtherMedicines(QUERY, 2);

        assertThat(committedSplSetIds()).containsExactly("A", "B");
        verify(medicineRepository, times(1)).fetchImageData("A");
    }

    @Test
    @DisplayName("[S3] 경계: DB에 이미 커밋된 A는 건너뛰고 나머지(B,C)만 저장한다")
    void s3_other_skipsAlreadyCommitted() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> medRepository.save(DbTestSupport.medicine("A")));
        fdaReturns(validItem("A"), validItem("B"), validItem("C"));

        service.saveOtherMedicines(QUERY, 2);

        assertThat(committedSplSetIds()).containsExactly("A", "B", "C");
        assertThat(db.committedCountBySplSetId("A")).as("A는 추가되지 않음").isEqualTo(1);
        verify(medicineRepository, never()).fetchImageData("A");
    }

    @Test
    @DisplayName("[S4] 실패: 한도 미달이면 RuntimeException이고, 이미 save()한 행도 롤백되어 DB에 0건이다")
    void s4_other_shortfallRollsBackAlreadySavedRows() {
        fdaReturns(validItem("A")); // 유효 1개, limit 3, 같은 페이지가 3회 반복됨

        assertThatThrownBy(() -> service.saveOtherMedicines(QUERY, 3)).isInstanceOf(RuntimeException.class);

        verify(medicineRepository, times(1)).fetchImageData("A"); // A는 변환·save()까지 진행됐다
        assertThat(stats.getEntityInsertCount()).as("롤백 전에 INSERT가 실제로 실행됨").isEqualTo(1);
        assertThat(db.committedMedicineCount()).as("롤백 후").isZero();
        assertThat(medRepository.existsBySplSetId("A")).isFalse();
    }

    @Test
    @DisplayName("[S5] S4 직후 충분한 데이터로 재호출하면 정상 저장된다 (요청 단위 Set은 호출 간에 남지 않는다)")
    void s5_other_retryAfterRollbackSucceeds() {
        fdaReturns(validItem("A"));
        assertThatThrownBy(() -> service.saveOtherMedicines(QUERY, 3)).isInstanceOf(RuntimeException.class);

        fdaReturns(validItem("A"), validItem("B"), validItem("C"));
        service.saveOtherMedicines(QUERY, 3);

        assertThat(committedSplSetIds()).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("[S6] 실패: 두 번째 항목이 DB 제약(varchar 255 초과)에 걸리면 첫 항목까지 롤백되어 0건이다")
    void s6_other_constraintViolationRollsBackEarlierSave() {
        fdaReturns(validItem("A"), item("[\"B\"]", "x".repeat(300)), validItem("C"));

        assertThatThrownBy(() -> service.saveOtherMedicines(QUERY, 3)).isInstanceOf(RuntimeException.class);

        assertThat(stats.getEntityInsertCount()).as("첫 항목 INSERT는 실행됐다").isEqualTo(1);
        assertThat(db.committedMedicineCount()).isZero();
    }

    @Test
    @DisplayName("[S10] 경계: ID가 없거나 무효(필드 없음/Unknown/공백/빈 배열)인 항목은 변환 전에 걸러지고 유효한 것만 저장된다")
    void s10_other_invalidIdsAreFilteredBeforeConversion() {
        fdaReturns(
                item(null, "NoId"),
                item("\"Unknown\"", "UnknownId"),
                item("\"   \"", "BlankId"),
                item("[]", "EmptyArray"),
                validItem("A"),
                validItem("B"));

        service.saveOtherMedicines(QUERY, 2);

        assertThat(committedSplSetIds()).containsExactly("A", "B");
        assertThat(imageFetchedIds()).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    @DisplayName("[S11] 저장된 ID로 다시 호출하면 모두 '이미 존재'로 판정되어 추가 저장·변환이 없다 (추출 ID == 저장 ID)")
    void s11_other_storedIdsAreRecognizedAsExisting() {
        fdaReturns(validItem("A"), validItem("B"), validItem("C"));
        service.saveOtherMedicines(QUERY, 3);
        org.mockito.Mockito.clearInvocations(medicineRepository);
        fdaReturns(validItem("A"), validItem("B"), validItem("C")); // clearInvocations는 stub을 지우지 않지만 명시

        assertThatThrownBy(() -> service.saveOtherMedicines(QUERY, 1)).isInstanceOf(RuntimeException.class);

        assertThat(db.committedMedicineCount()).as("변동 없음").isEqualTo(3);
        verify(medicineRepository, never()).fetchImageData(anyString());
    }

    // ---------------------------------------------------------------- saveMedicinesByCategory (TransactionTemplate + saveAll)

    @Test
    @DisplayName("[S7] 정상: 카테고리 저장 limit 3 -> saveAll 한 번으로 3행이 커밋된다")
    void s7_category_normal() {
        fdaReturns(validItem("A"), validItem("B"), validItem("C"));

        List<Medicine> result = service.saveMedicinesByCategory(MedicineCategory.PAIN_RELIEF, 3);

        assertThat(result).hasSize(3);
        assertThat(committedSplSetIds()).containsExactly("A", "B", "C");
        assertThat(medRepository.findAll()).allSatisfy(m -> assertThat(m.getCategory()).isEqualTo(MedicineCategory.PAIN_RELIEF));
    }

    @Test
    @DisplayName("[S8] 실패: 카테고리 한도 미달이면 예외이고, 저장 단계에 들어가기 전이므로 INSERT 자체가 없다")
    void s8_category_shortfallNeverReachesSave() {
        fdaReturns(validItem("A"));

        assertThatThrownBy(() -> service.saveMedicinesByCategory(MedicineCategory.PAIN_RELIEF, 3))
                .isInstanceOf(RuntimeException.class);

        assertThat(stats.getEntityInsertCount()).as("saveOtherMedicines(S4)와 달리 INSERT가 실행되지 않음").isZero();
        assertThat(db.committedMedicineCount()).isZero();
    }

    @Test
    @DisplayName("[S9] 실패: saveAll 중 한 항목이 DB 제약에 걸리면 앞 항목 INSERT도 롤백되어 0건이다")
    void s9_category_saveAllRollsBackOnConstraintViolation() {
        fdaReturns(validItem("A"), item("[\"B\"]", "x".repeat(300)), validItem("C"));

        assertThatThrownBy(() -> service.saveMedicinesByCategory(MedicineCategory.PAIN_RELIEF, 3))
                .isInstanceOf(RuntimeException.class);

        assertThat(stats.getEntityInsertCount()).as("실패 전 A의 INSERT는 실행됨").isEqualTo(1);
        assertThat(db.committedMedicineCount()).isZero();
    }

    @Test
    @DisplayName("[S7b] 경계: 카테고리 저장에서도 응답 내 중복 ID(A,A,B)와 기존 커밋 ID(C)를 건너뛴다")
    void s7b_category_skipsDuplicatesAndExisting() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> medRepository.save(DbTestSupport.medicine("C")));
        fdaReturns(validItem("A"), validItem("A"), validItem("C"), validItem("B"));

        service.saveMedicinesByCategory(MedicineCategory.PAIN_RELIEF, 2);

        assertThat(committedSplSetIds()).containsExactly("A", "B", "C");
        assertThat(db.committedCountBySplSetId("A")).isEqualTo(1);
        assertThat(Stream.of("A", "B").allMatch(id -> imageFetchedIds().contains(id))).isTrue();
        verify(medicineRepository, never()).fetchImageData("C");
    }
}
