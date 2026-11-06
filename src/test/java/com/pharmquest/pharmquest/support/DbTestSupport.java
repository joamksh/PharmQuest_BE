package com.pharmquest.pharmquest.support;

import com.pharmquest.pharmquest.domain.medicine.data.Medicine;
import com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * B-010: JPA 트랜잭션 밖에서 DB를 관찰하는 도우미.
 *
 * <p>매 호출마다 <b>새 JDBC 연결</b>(autocommit)을 얻어 쿼리한다. 테스트가 진행 중인 트랜잭션은 다른 연결을
 * 쓰므로, 이 클래스가 보는 값은 InnoDB 기본 격리(REPEATABLE READ)에서 <b>커밋된 데이터뿐</b>이다.
 * 이것이 flush(DB로 SQL 전송)와 commit(다른 연결에 보임)을 구분하는 근거다.
 */
public class DbTestSupport {

    private final DataSource dataSource;

    public DbTestSupport(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 커밋된 medicine 행 수. */
    public long committedMedicineCount() {
        return queryLong("SELECT COUNT(*) FROM medicine");
    }

    /** 커밋된 행 중 해당 splSetId의 수 (대소문자는 컬럼 collation을 따른다). */
    public long committedCountBySplSetId(String splSetId) {
        return queryLong("SELECT COUNT(*) FROM medicine WHERE spl_set_id = '" + escape(splSetId) + "'");
    }

    /** 커밋된 행의 brand_name. 행이 없으면 null. */
    public String committedBrandName(long id) {
        return queryString("SELECT brand_name FROM medicine WHERE id = " + id);
    }

    /** 테스트 스키마의 spl_set_id 컬럼 collation (대소문자 구분 여부 판단용). */
    public String splSetIdCollation() {
        return queryString("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medicine' AND COLUMN_NAME = 'spl_set_id'");
    }

    public String serverVersion() {
        return queryString("SELECT VERSION()");
    }

    public String sqlMode() {
        return queryString("SELECT @@SESSION.sql_mode");
    }

    public String transactionIsolation() {
        return queryString("SELECT @@SESSION.transaction_isolation");
    }

    /** 테스트 간 격리를 위해 커밋된 데이터를 모두 지운다 (테스트 컨테이너 전용). */
    public void deleteAllMedicines() {
        execute("DELETE FROM medicine");
    }

    private long queryLong(String sql) {
        String value = queryString(sql);
        return value == null ? 0 : Long.parseLong(value);
    }

    private String queryString(String sql) {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("관찰용 쿼리 실패: " + sql, e);
        }
    }

    private void execute(String sql) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("관찰용 문장 실패: " + sql, e);
        }
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }

    /** 모든 필드가 채워진 유효한 Medicine. */
    public static Medicine medicine(String splSetId) {
        Medicine m = new Medicine();
        m.setBrandName("Brand-" + splSetId);
        m.setGenericName("Generic");
        m.setSubstanceName("Substance");
        m.setActiveIngredient("Ingredient");
        m.setPurpose("Pain");
        m.setIndicationsAndUsage("Use");
        m.setDosageAndAdministration("Dose");
        m.setWarnings("Warn");
        m.setSplSetId(splSetId);
        m.setImgUrl("http://localhost.invalid/img.png");
        m.setCategory(MedicineCategory.OTHER);
        m.setCountry("USA");
        return m;
    }

    public static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }
}
