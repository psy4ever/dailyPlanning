package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keep owner IDs and all foreign keys intact while replacing human-chosen login IDs. */
public class V3__server_assigned_numeric_uids extends BaseJavaMigration {
    private record Account(String id, String oldUid) {}

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE cloud_accounts ADD COLUMN legacy_uid VARCHAR(80)");
        }
        List<Account> accounts = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id,uid FROM cloud_accounts ORDER BY CASE WHEN uid='yuan' THEN 0 ELSE 1 END,uid")) {
            while (rows.next()) accounts.add(new Account(rows.getString(1), rows.getString(2)));
        }
        if (accounts.size() > 99_999_999) throw new IllegalStateException("UID 空间已用尽");
        // Free every old unique UID before assigning the new contiguous range.
        try (PreparedStatement update = connection.prepareStatement("UPDATE cloud_accounts SET uid=? WHERE id=?")) {
            for (Account account : accounts) {
                update.setString(1, "~" + account.id()); update.setString(2, account.id()); update.addBatch();
            }
            update.executeBatch();
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE cloud_accounts SET uid=?,legacy_uid=? WHERE id=?")) {
            for (int index = 0; index < accounts.size(); index++) {
                Account account = accounts.get(index);
                update.setString(1, String.format(Locale.ROOT, "%08d", index + 1));
                update.setString(2, account.oldUid());
                update.setString(3, account.id());
                update.addBatch();
            }
            update.executeBatch();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE cloud_accounts ADD CONSTRAINT cloud_accounts_legacy_uid_unique UNIQUE (legacy_uid)");
            statement.execute("CREATE TABLE cloud_uid_sequence (id SMALLINT PRIMARY KEY, next_value INT NOT NULL)");
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO cloud_uid_sequence(id,next_value) VALUES(1,?)")) {
            insert.setInt(1, accounts.size() + 1); insert.executeUpdate();
        }
    }
}
