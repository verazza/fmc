package velocity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;

public interface DatabaseInterface {
    // メソッドの宣言
	Connection getConnection(String customDatabase) throws SQLException, ClassNotFoundException;
    Connection getConnection() throws SQLException, ClassNotFoundException;
    String createPlaceholders(int count);
    String createQueryPart(Set<String> keySet);
    void setPreparedStatementValue(PreparedStatement ps, int parameterIndex, Object value) throws SQLException;
    Class<?> getTypes(Object value);
}
