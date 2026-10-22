package com.dayoudu.cloud;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

class UidMigrationTest {
    @Test void upgradesLegacyAccountsWithoutChangingOwnersOrRecords() throws Exception {
        String url="jdbc:h2:mem:uid-migration-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        verifyMigration(url,"sa","");
    }
    @Test void upgradesOnIsolatedMySqlWhenConfigured() throws Exception {
        String url=System.getenv("UID_MIGRATION_MYSQL_URL");
        Assumptions.assumeTrue(url!=null&&!url.isBlank(),"Dedicated MySQL migration schema not configured");
        verifyMigration(url,System.getenv().getOrDefault("UID_MIGRATION_MYSQL_USER","root"),
            System.getenv().getOrDefault("UID_MIGRATION_MYSQL_PASSWORD",""));
    }
    private void verifyMigration(String url,String user,String password) throws Exception {
        Flyway.configure().dataSource(url,user,password).target(MigrationVersion.fromVersion("2")).load().migrate();
        String owner=UUID.randomUUID().toString(),record=UUID.randomUUID().toString();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO cloud_accounts(id,uid,account_name,password_hash,change_seq) VALUES('"+owner+"','yuan','原昵称','existing-hash',0)");
            statement.executeUpdate("INSERT INTO cloud_records(owner_id,kind,record_id,server_version,payload) VALUES('"+owner+"','ledger','"+record+"',1,'{}')");
        }
        Flyway.configure().dataSource(url,user,password).load().migrate();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            try(var row=statement.executeQuery("SELECT uid,legacy_uid,account_name,password_hash FROM cloud_accounts WHERE id='"+owner+"'")) {
                assertTrue(row.next());assertEquals("00000001",row.getString(1));assertEquals("yuan",row.getString(2));
                assertEquals("原昵称",row.getString(3));assertEquals("existing-hash",row.getString(4));
            }
            try(var row=statement.executeQuery("SELECT owner_id FROM cloud_records WHERE record_id='"+record+"'")) {
                assertTrue(row.next());assertEquals(owner,row.getString(1));
            }
            try(var row=statement.executeQuery("SELECT next_value FROM cloud_uid_sequence WHERE id=1")) {
                assertTrue(row.next());assertEquals(2,row.getInt(1));
            }
        }
    }
}
