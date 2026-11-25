package ec.yawar.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SafeErrorsTest {

    @Test
    void keepsClassesAndSqlStateButNeverTheMessage() {
        SQLException sql = new SQLException("Key (email)=(ana@example.ec) already exists", "23505");
        RuntimeException error = new IllegalStateException("could not insert ana@example.ec", sql);

        String described = SafeErrors.describe(error);

        assertThat(described).isEqualTo(
                "java.lang.IllegalStateException caused by java.sql.SQLException (SQLState 23505)");
        assertThat(described).doesNotContain("ana@example.ec");
    }

    @Test
    void handlesSimpleAndNullErrors() {
        assertThat(SafeErrors.describe(new IllegalArgumentException("x"))).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(SafeErrors.describe(null)).isEqualTo("unknown");
    }
}
