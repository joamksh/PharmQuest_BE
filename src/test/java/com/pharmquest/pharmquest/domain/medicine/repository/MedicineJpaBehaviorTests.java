package com.pharmquest.pharmquest.domain.medicine.repository;

import com.pharmquest.pharmquest.domain.medicine.data.Medicine;
import com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory;
import com.pharmquest.pharmquest.support.DbTestSupport;
import com.pharmquest.pharmquest.support.MySqlContainerTestConfig;
import com.pharmquest.pharmquest.support.RequiresDocker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-010: Testcontainers MySQL에서 Medicine/MedRepository의 실제 JPA 동작을 검증한다.
 *
 * <h3>방법</h3>
 * <ul>
 *   <li>테스트 메서드에 {@code @Transactional}을 쓰지 않는다 (테스트 전체가 한 트랜잭션이 되면 commit/rollback을 구분할 수 없다).
 *       트랜잭션은 {@link TransactionTemplate}으로 직접 제어한다.</li>
 *   <li>커밋 여부는 {@link DbTestSupport}의 <b>별도 JDBC 연결</b>로 관찰한다 (커밋된 데이터만 보인다).</li>
 *   <li>INSERT/UPDATE 실행 시점은 Hibernate {@link Statistics}의 엔티티 insert/update 횟수로 본다.</li>
 * </ul>
 *
 * <h3>한계</h3>
 * 스키마는 {@code ddl-auto: create-drop}으로 <b>엔티티에서 생성한 테스트용</b>이다. 운영 DDL(컬럼 타입·길이·charset·collation·
 * unique/index)과 같다는 보장이 없다. 컨테이너 이미지(mysql:8.0)도 운영 버전을 모르고 고른 값이다.
 * 따라서 J 시리즈(JPA/트랜잭션 의미)는 비교적 일반적이지만 M 시리즈(길이·제약·collation)의 결과는 <b>테스트 DB 기준</b>이다.
 */
@RequiresDocker
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlContainerTestConfig.class)
class MedicineJpaBehaviorTests {

    @Autowired
    private MedRepository medRepository;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private EntityManagerFactory emf;
    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private DbTestSupport db;
    private Statistics stats;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        db = new DbTestSupport(dataSource);
        db.deleteAllMedicines();
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
    }

    // ---------------------------------------------------------------- J: IDENTITY / flush / commit / rollback

    @Test
    @DisplayName("[J1] IDENTITY: save() 시점에 INSERT가 실행되고 id가 즉시 채워지지만, 커밋 전에는 다른 연결에 보이지 않는다")
    void j1_identityInsertsAtSave_butNotVisibleBeforeCommit() {
        tx.executeWithoutResult(status -> {
            stats.clear();
            Medicine m = DbTestSupport.medicine("J1");

            assertThat(m.getId()).isNull();
            assertThat(stats.getEntityInsertCount()).isZero();

            medRepository.save(m);

            // flush를 호출하지 않았다: IDENTITY는 id를 얻기 위해 persist 시점에 INSERT를 실행한다
            assertThat(m.getId()).isNotNull();
            assertThat(stats.getEntityInsertCount()).as("save() 직후 INSERT 실행 횟수").isEqualTo(1);
            assertThat(db.committedMedicineCount()).as("커밋 전 다른 연결에서 본 행 수").isZero();
        });

        assertThat(db.committedMedicineCount()).as("커밋 후").isEqualTo(1);
    }

    @Test
    @DisplayName("[J2] IDENTITY: 이미 실행된 INSERT는 flush()가 다시 실행하지 않는다 (flush는 INSERT 시점이 아니다)")
    void j2_flushDoesNotInsertAgain() {
        tx.executeWithoutResult(status -> {
            stats.clear();
            medRepository.save(DbTestSupport.medicine("J2"));
            assertThat(stats.getEntityInsertCount()).isEqualTo(1);

            em.flush();

            assertThat(stats.getEntityInsertCount()).as("flush 후에도 INSERT는 1회").isEqualTo(1);
            assertThat(db.committedMedicineCount()).as("flush는 커밋이 아니다").isZero();
        });
    }

    @Test
    @DisplayName("[J3] flush와 commit 구분: 변경 감지 UPDATE는 flush 때 실행되고, 다른 연결에는 commit 후에야 보인다")
    void j3_updateRunsAtFlush_visibleAfterCommit() {
        Long id = tx.execute(s -> medRepository.save(DbTestSupport.medicine("J3")).getId());
        assertThat(db.committedBrandName(id)).isEqualTo("Brand-J3");

        tx.executeWithoutResult(status -> {
            Medicine m = medRepository.findById(id).orElseThrow();
            stats.clear();
            m.setBrandName("Changed");

            assertThat(stats.getEntityUpdateCount()).as("flush 전").isZero();

            em.flush();

            assertThat(stats.getEntityUpdateCount()).as("flush 후 UPDATE 실행").isEqualTo(1);
            assertThat(db.committedBrandName(id)).as("flush 후 commit 전, 다른 연결이 본 값").isEqualTo("Brand-J3");
        });

        assertThat(db.committedBrandName(id)).as("commit 후").isEqualTo("Changed");
    }

    @Test
    @DisplayName("[J4] 같은 트랜잭션 안에서는 save()한 행이 existsBySplSetId에 보이고, 다른 연결에는 보이지 않는다")
    void j4_existsSeesUncommittedSaveInSameTransaction() {
        tx.executeWithoutResult(status -> {
            assertThat(medRepository.existsBySplSetId("J4")).isFalse();

            medRepository.save(DbTestSupport.medicine("J4"));

            assertThat(medRepository.existsBySplSetId("J4")).as("같은 트랜잭션").isTrue();
            assertThat(db.committedCountBySplSetId("J4")).as("다른 연결").isZero();
        });
    }

    @Test
    @DisplayName("[J5] commit 후에는 새 트랜잭션과 다른 연결 모두에서 보인다")
    void j5_visibleAfterCommit() {
        tx.executeWithoutResult(status -> medRepository.save(DbTestSupport.medicine("J5")));

        assertThat(db.committedCountBySplSetId("J5")).isEqualTo(1);
        assertThat(medRepository.existsBySplSetId("J5")).isTrue();
        assertThat(medRepository.findBySplSetId("J5")).isPresent();
    }

    @Test
    @DisplayName("[J6] rollback: INSERT가 이미 실행됐어도 예외로 롤백되면 행이 남지 않는다")
    void j6_rollbackUndoesAlreadyExecutedInsert() {
        List<Long> idHolder = new ArrayList<>();
        List<Long> insertCountHolder = new ArrayList<>();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            stats.clear();
            Medicine m = medRepository.save(DbTestSupport.medicine("J6"));
            idHolder.add(m.getId());
            insertCountHolder.add(stats.getEntityInsertCount());
            assertThat(medRepository.existsBySplSetId("J6")).as("롤백 전 같은 트랜잭션").isTrue();
            throw new IllegalStateException("의도된 롤백");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(insertCountHolder).as("롤백 전에 INSERT가 이미 실행됨").containsExactly(1L);
        assertThat(idHolder.get(0)).isNotNull();
        assertThat(db.committedMedicineCount()).as("롤백 후 다른 연결").isZero();
        assertThat(medRepository.existsBySplSetId("J6")).as("롤백 후 새 트랜잭션").isFalse();
    }

    @Test
    @DisplayName("[J7] 롤백해도 AUTO_INCREMENT 값은 되돌아가지 않는다 (id가 비어 있는 구멍이 생긴다)")
    void j7_autoIncrementIsNotRolledBack() {
        List<Long> rolledBackId = new ArrayList<>();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            rolledBackId.add(medRepository.save(DbTestSupport.medicine("J7a")).getId());
            throw new IllegalStateException("의도된 롤백");
        })).isInstanceOf(IllegalStateException.class);

        Long nextId = tx.execute(s -> medRepository.save(DbTestSupport.medicine("J7b")).getId());

        assertThat(nextId).isGreaterThan(rolledBackId.get(0));
    }

    @Test
    @DisplayName("[J8] saveAll: 중간 항목이 DB 제약에 걸려도 앞 항목의 INSERT는 이미 실행됐고, 트랜잭션 전체가 롤백된다")
    void j8_saveAllIsAtomicOnFailure() {
        List<Long> insertsBeforeFailure = new ArrayList<>();
        List<Long> committedViewBeforeFailure = new ArrayList<>();

        Medicine ok1 = DbTestSupport.medicine("J8-1");
        Medicine ok2 = DbTestSupport.medicine("J8-2");
        Medicine bad = DbTestSupport.medicine("J8-3");
        bad.setBrandName(DbTestSupport.repeat('x', 256)); // varchar(255) 초과

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            stats.clear();
            try {
                medRepository.saveAll(List.of(ok1, ok2, bad));
            } finally {
                insertsBeforeFailure.add(stats.getEntityInsertCount());
                committedViewBeforeFailure.add(db.committedMedicineCount());
            }
        })).isInstanceOf(DataAccessException.class);

        assertThat(insertsBeforeFailure).as("실패 전에 성공한 INSERT 수").containsExactly(2L);
        assertThat(committedViewBeforeFailure).as("실패 시점, 다른 연결").containsExactly(0L);
        assertThat(db.committedMedicineCount()).as("롤백 후 전체 0건").isZero();
    }

    // ---------------------------------------------------------------- M: 매핑 / 스키마 / 제약 (테스트 스키마 기준)

    @Test
    @DisplayName("[M1] 모든 필드(한글, enum, 긴 TEXT)를 저장하고 새 트랜잭션에서 그대로 읽는다")
    void m1_roundTripAllFields() {
        Medicine m = DbTestSupport.medicine("M1");
        m.setBrandName("타이레놀 Tylenol");
        m.setWarnings("경고 ".repeat(5000)); // 약 1만 자(바이트로는 더 큼), TEXT 범위 안
        m.setCategory(MedicineCategory.PAIN_RELIEF);

        Long id = tx.execute(s -> medRepository.save(m).getId());
        em.clear();

        Medicine found = tx.execute(s -> medRepository.findById(id).orElseThrow());
        assertThat(found).usingRecursiveComparison().isEqualTo(m);
    }

    @ParameterizedTest(name = "[M2] brandName {0}자")
    @ValueSource(ints = {255, 256})
    @DisplayName("[M2] varchar(255) 경계: 255자는 저장되고 256자는 거부되어 롤백된다 (테스트 스키마 기준)")
    void m2_varcharBoundary(int length) {
        Medicine m = DbTestSupport.medicine("M2-" + length);
        m.setBrandName(DbTestSupport.repeat('b', length));

        if (length <= 255) {
            tx.executeWithoutResult(s -> medRepository.save(m));
            assertThat(db.committedMedicineCount()).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> medRepository.save(m)))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("Data too long");
            assertThat(db.committedMedicineCount()).isZero();
        }
    }

    @ParameterizedTest(name = "[M3] warnings {0}자(ASCII)")
    @ValueSource(ints = {65_535, 65_536})
    @DisplayName("[M3] TEXT(65,535바이트) 경계: 한도까지는 저장되고 초과하면 거부된다 (테스트 스키마 기준, 운영 컬럼 타입은 미확인)")
    void m3_textBoundary(int length) {
        Medicine m = DbTestSupport.medicine("M3-" + length);
        m.setWarnings(DbTestSupport.repeat('w', length));

        if (length <= 65_535) {
            tx.executeWithoutResult(s -> medRepository.save(m));
            assertThat(db.committedMedicineCount()).isEqualTo(1);
        } else {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> medRepository.save(m)))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("Data too long");
            assertThat(db.committedMedicineCount()).isZero();
        }
    }

    /**
     * [현행 동작 기록] 이 테스트는 "중복 허용이 올바르다"는 요구사항을 확정하지 않는다.
     * splSetId에 unique 제약이 없는 현재 매핑에서 DB가 무엇을 하는지 기록할 뿐이다 (B-004).
     * 운영 DDL에 unique가 있는지는 이 테스트로 알 수 없다. unique를 추가하면 이 테스트는 의도적으로 바뀌어야 한다.
     */
    @Test
    @DisplayName("[M4 현행 동작 기록] 같은 splSetId를 두 번 저장하면 둘 다 들어간다 (unique 제약 없음)")
    void m4_recordsDuplicateSplSetIdIsAcceptedWithoutUniqueConstraint() {
        tx.executeWithoutResult(s -> medRepository.save(DbTestSupport.medicine("M4")));
        tx.executeWithoutResult(s -> medRepository.save(DbTestSupport.medicine("M4")));

        assertThat(db.committedCountBySplSetId("M4")).as("현행: 중복 행 2개").isEqualTo(2);
        assertThat(medRepository.existsBySplSetId("M4")).isTrue();
    }

    /** M4의 결과로 중복 행이 생긴 상태에서 findBySplSetId가 어떻게 되는지 기록한다 (MedicineConverter.convertToDetail이 사용). */
    @Test
    @DisplayName("[M4b 현행 동작 기록] 중복 행이 있으면 findBySplSetId(Optional)는 예외를 던진다")
    void m4b_recordsFindBySplSetIdWithDuplicateRows() {
        tx.executeWithoutResult(s -> medRepository.save(DbTestSupport.medicine("M4B")));
        tx.executeWithoutResult(s -> medRepository.save(DbTestSupport.medicine("M4B")));

        assertThatThrownBy(() -> medRepository.findBySplSetId("M4B"))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);
    }

    @Test
    @DisplayName("[M5] 대소문자만 다른 splSetId의 existsBySplSetId 결과는 컬럼 collation(_ci 여부)을 따른다 (테스트 스키마 기준)")
    void m5_caseSensitivityFollowsColumnCollation() {
        tx.executeWithoutResult(s -> medRepository.save(DbTestSupport.medicine("abc")));

        String collation = db.splSetIdCollation();
        boolean caseInsensitive = collation != null && collation.endsWith("_ci");
        System.out.println("[B-010 M5] server=" + db.serverVersion() + " collation=" + collation
                + " existsBySplSetId(\"ABC\")=" + medRepository.existsBySplSetId("ABC"));

        assertThat(medRepository.existsBySplSetId("ABC")).isEqualTo(caseInsensitive);
        assertThat(medRepository.existsBySplSetId("abc")).isTrue();
    }

    @Test
    @DisplayName("[환경 기록] 테스트 DB의 버전, sql_mode, 격리 수준을 출력한다 (운영 값과 다를 수 있음)")
    void recordTestDatabaseEnvironment() {
        System.out.println("[B-010 ENV] version=" + db.serverVersion() + " isolation=" + db.transactionIsolation()
                + " sql_mode=" + db.sqlMode());
        assertThat(db.serverVersion()).isNotBlank();
    }
}
