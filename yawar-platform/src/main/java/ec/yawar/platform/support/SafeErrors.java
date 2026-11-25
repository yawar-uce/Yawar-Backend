package ec.yawar.platform.support;

import java.sql.SQLException;

/**
 * Describes an exception for logs and message headers <b>without its message text</b>: driver and JSON errors
 * often echo values (an email in a duplicate-key error, a payload fragment), which must not reach logs or the
 * DLQ (LOPDP). Keeps the exception class, the root-cause class and the SQLState when there is one.
 */
public final class SafeErrors {

    private SafeErrors() {
    }

    public static String describe(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable root = error;
        String sqlState = null;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (sqlState == null && t instanceof SQLException sql && sql.getSQLState() != null) {
                sqlState = sql.getSQLState();
            }
            root = t;
            if (t.getCause() == t) {
                break;
            }
        }
        StringBuilder out = new StringBuilder(error.getClass().getName());
        if (root != error) {
            out.append(" caused by ").append(root.getClass().getName());
        }
        if (sqlState != null) {
            out.append(" (SQLState ").append(sqlState).append(')');
        }
        return out.toString();
    }
}
