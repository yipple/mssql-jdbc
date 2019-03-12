/*
 * Microsoft JDBC Driver for SQL Server Copyright(c) Microsoft Corporation All rights reserved. This program is made
 * available under the terms of the MIT License. See the LICENSE file in the project root for more information.
 */
package com.microsoft.sqlserver.jdbc.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import javax.sql.ConnectionEvent;
import javax.sql.PooledConnection;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import com.microsoft.sqlserver.jdbc.ISQLServerConnection;
import com.microsoft.sqlserver.jdbc.RandomUtil;
import com.microsoft.sqlserver.jdbc.SQLServerConnection;
import com.microsoft.sqlserver.jdbc.SQLServerConnectionPoolDataSource;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.microsoft.sqlserver.jdbc.SQLServerDriver;
import com.microsoft.sqlserver.jdbc.TestResource;
import com.microsoft.sqlserver.testframework.AbstractSQLGenerator;
import com.microsoft.sqlserver.testframework.AbstractTest;


@RunWith(JUnitPlatform.class)
@Tag("AzureDWTest")
public class ConnectionDriverTest extends AbstractTest {
    // If no retry is done, the function should at least exit in 5 seconds
    static int threshHoldForNoRetryInMilliseconds = 5000;
    static int loginTimeOutInSeconds = 10;

    String randomServer = RandomUtil.getIdentifier("Server");

    /**
     * test connection properties
     * 
     * @throws SQLException
     */
    @Test
    public void testConnectionDriver() throws SQLException {
        SQLServerDriver d = new SQLServerDriver();
        Properties info = new Properties();
        StringBuffer url = new StringBuffer();
        url.append("jdbc:sqlserver://" + randomServer + ";packetSize=512;");
        // test defaults
        DriverPropertyInfo[] infoArray = d.getPropertyInfo(url.toString(), info);
        for (DriverPropertyInfo anInfoArray1 : infoArray) {
            logger.fine(anInfoArray1.name);
            logger.fine(anInfoArray1.description);
            logger.fine(Boolean.valueOf(anInfoArray1.required).toString());
            logger.fine(anInfoArray1.value);
        }

        url.append("encrypt=true; trustStore=someStore; trustStorePassword=somepassword;");
        url.append("hostNameInCertificate=someHost; trustServerCertificate=true");
        infoArray = d.getPropertyInfo(url.toString(), info);
        for (DriverPropertyInfo anInfoArray : infoArray) {
            if (anInfoArray.name.equals("encrypt")) {
                assertTrue(anInfoArray.value.equals("true"), TestResource.getResource("R_valuesAreDifferent"));
            }
            if (anInfoArray.name.equals("trustStore")) {
                assertTrue(anInfoArray.value.equals("someStore"), TestResource.getResource("R_valuesAreDifferent"));
            }
            if (anInfoArray.name.equals("trustStorePassword")) {
                assertTrue(anInfoArray.value.equals("somepassword"), TestResource.getResource("R_valuesAreDifferent"));
            }
            if (anInfoArray.name.equals("hostNameInCertificate")) {
                assertTrue(anInfoArray.value.equals("someHost"), TestResource.getResource("R_valuesAreDifferent"));
            }
        }
    }

    /**
     * test connection properties with SQLServerDataSource
     */
    @Test
    public void testDataSource() {
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setUser("User");
        ds.setPassword("sUser");
        ds.setApplicationName("User");
        ds.setURL("jdbc:sqlserver://" + randomServer + ";packetSize=512");

        String trustStore = "Store";
        String trustStorePassword = "pwd";

        ds.setTrustStore(trustStore);
        ds.setEncrypt(true);
        ds.setTrustStorePassword(trustStorePassword);
        ds.setTrustServerCertificate(true);
        assertEquals(trustStore, ds.getTrustStore(), TestResource.getResource("R_valuesAreDifferent"));
        assertEquals(true, ds.getEncrypt(), TestResource.getResource("R_valuesAreDifferent"));
        assertEquals(true, ds.getTrustServerCertificate(), TestResource.getResource("R_valuesAreDifferent"));
    }

    @Test
    public void testEncryptedConnection() throws SQLException {
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setApplicationName("User");
        ds.setURL(connectionString);
        ds.setEncrypt(true);
        ds.setTrustServerCertificate(true);
        ds.setPacketSize(8192);
        try (Connection con = ds.getConnection()) {}
    }

    @Test
    public void testJdbcDriverMethod() throws SQLFeatureNotSupportedException {
        SQLServerDriver serverDriver = new SQLServerDriver();
        Logger logger = serverDriver.getParentLogger();
        assertEquals(logger.getName(), "com.microsoft.sqlserver.jdbc",
                TestResource.getResource("R_parrentLoggerNameWrong"));
    }

    @Test
    public void testJdbcDataSourceMethod() throws SQLFeatureNotSupportedException {
        SQLServerDataSource fxds = new SQLServerDataSource();
        Logger logger = fxds.getParentLogger();
        assertEquals(logger.getName(), "com.microsoft.sqlserver.jdbc",
                TestResource.getResource("R_parrentLoggerNameWrong"));
    }

    class MyEventListener implements javax.sql.ConnectionEventListener {
        boolean connClosed = false;
        boolean errorOccurred = false;

        public MyEventListener() {}

        public void connectionClosed(ConnectionEvent event) {
            connClosed = true;
        }

        public void connectionErrorOccurred(ConnectionEvent event) {
            errorOccurred = true;
        }
    }

    /**
     * Attach the Event listener and listen for connection events, fatal errors should not close the pooled connection
     * objects
     * 
     * @throws SQLException
     */
    @Test
    public void testConnectionEvents() throws SQLException {
        assumeFalse(isSqlAzure(), TestResource.getResource("R_skipAzure"));

        SQLServerConnectionPoolDataSource mds = new SQLServerConnectionPoolDataSource();
        mds.setURL(connectionString);
        PooledConnection pooledConnection = mds.getPooledConnection();

        // Attach the Event listener and listen for connection events.
        MyEventListener myE = new MyEventListener();
        pooledConnection.addConnectionEventListener(myE); // ConnectionListener implements ConnectionEventListener

        try (Connection con = pooledConnection.getConnection();
                Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE)) {

            boolean exceptionThrown = false;
            try {
                // raise a severe exception and make sure that the connection is not
                // closed.
                stmt.executeUpdate("RAISERROR ('foo', 20,1) WITH LOG");
            } catch (Exception e) {
                exceptionThrown = true;
            }
            assertTrue(exceptionThrown, TestResource.getResource("R_expectedExceptionNotThrown"));

            // Check to see if error occurred.
            assertTrue(myE.errorOccurred, TestResource.getResource("R_errorNotCalled"));
        } finally {
            // make sure that connection is closed.
            if (null != pooledConnection)
                pooledConnection.close();
        }
    }

    @Test
    public void testConnectionPoolGetTwice() throws SQLException {
        assumeFalse(isSqlAzure(), TestResource.getResource("R_skipAzure"));

        SQLServerConnectionPoolDataSource mds = new SQLServerConnectionPoolDataSource();
        mds.setURL(connectionString);
        PooledConnection pooledConnection = mds.getPooledConnection();

        // Attach the Event listener and listen for connection events.
        MyEventListener myE = new MyEventListener();
        pooledConnection.addConnectionEventListener(myE); // ConnectionListener implements ConnectionEventListener

        try (Connection con = pooledConnection.getConnection();
                Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE)) {
            // raise a non severe exception and make sure that the connection is not closed.
            stmt.executeUpdate("RAISERROR ('foo', 3,1)");
            // not a serious error there should not be any errors.
            assertTrue(!myE.errorOccurred, TestResource.getResource("R_errorCalled"));
            // check to make sure that connection is not closed.
            assertTrue(!con.isClosed(), TestResource.getResource("R_connectionIsClosed"));
            stmt.close();
            con.close();
            // check to make sure that connection is closed.
            assertTrue(con.isClosed(), TestResource.getResource("R_connectionIsNotClosed"));
        } finally {
            // make sure that connection is closed.
            if (null != pooledConnection)
                pooledConnection.close();
        }
    }

    @Test
    public void testConnectionClosed() throws SQLException {
        assumeFalse(isSqlAzure(), TestResource.getResource("R_skipAzure"));

        SQLServerDataSource mds = new SQLServerDataSource();
        mds.setURL(connectionString);
        try (Connection con = mds.getConnection();
                Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE)) {
            boolean exceptionThrown = false;
            try {
                stmt.executeUpdate("RAISERROR ('foo', 20,1) WITH LOG");
            } catch (Exception e) {
                exceptionThrown = true;
            }
            assertTrue(exceptionThrown, TestResource.getResource("R_expectedExceptionNotThrown"));

            // check to make sure that connection is closed.
            assertTrue(con.isClosed(), TestResource.getResource("R_connectionIsNotClosed"));
        } catch (Exception e) {
            fail(TestResource.getResource("R_unexpectedErrorMessage") + e.toString());
        }
    }

    @Test
    public void testIsWrapperFor() throws SQLException, ClassNotFoundException {
        try (Connection conn = DriverManager.getConnection(connectionString);
                SQLServerConnection ssconn = (SQLServerConnection) conn) {
            boolean isWrapper;
            isWrapper = ssconn.isWrapperFor(ssconn.getClass());
            MessageFormat form = new MessageFormat(TestResource.getResource("R_supportUnwrapping"));
            Object[] msgArgs1 = {"SQLServerConnection"};

            assertTrue(isWrapper, form.format(msgArgs1));
            assertEquals(ISQLServerConnection.TRANSACTION_SNAPSHOT, ISQLServerConnection.TRANSACTION_SNAPSHOT,
                    TestResource.getResource("R_cantAccessSnapshot"));

            isWrapper = ssconn.isWrapperFor(Class.forName("com.microsoft.sqlserver.jdbc.ISQLServerConnection"));
            Object[] msgArgs2 = {"ISQLServerConnection"};
            assertTrue(isWrapper, form.format(msgArgs2));
            ssconn.unwrap(Class.forName("com.microsoft.sqlserver.jdbc.ISQLServerConnection"));
            assertEquals(ISQLServerConnection.TRANSACTION_SNAPSHOT, ISQLServerConnection.TRANSACTION_SNAPSHOT,
                    TestResource.getResource("R_cantAccessSnapshot"));

            ssconn.unwrap(Class.forName("java.sql.Connection"));
        }
    }

    @Test
    public void testNewConnection() throws SQLException {
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(connectionString)) {
            assertTrue(conn.isValid(0), TestResource.getResource("R_newConnectionShouldBeValid"));
        }
    }

    @Test
    public void testClosedConnection() throws SQLException {
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(connectionString)) {
            conn.close();
            assertTrue(!conn.isValid(0), TestResource.getResource("R_closedConnectionShouldBeInvalid"));
        }
    }

    @Test
    public void testNegativeTimeout() throws Exception {
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(connectionString)) {
            try {
                conn.isValid(-42);
                throw new Exception(TestResource.getResource("R_noExceptionNegativeTimeout"));
            } catch (SQLException e) {
                MessageFormat form = new MessageFormat(TestResource.getResource("R_invalidQueryTimeout"));
                Object[] msgArgs = {"-42"};

                assertEquals(e.getMessage(), form.format(msgArgs), TestResource.getResource("R_wrongExceptionMessage"));
            }
        }
    }

    @Test
    public void testDeadConnection() throws SQLException {
        assumeFalse(isSqlAzure(), TestResource.getResource("R_skipAzure"));

        String tableName = RandomUtil.getIdentifier("ConnectionTestTable");
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager
                .getConnection(connectionString + ";responseBuffering=adaptive");
                Statement stmt = conn.createStatement()) {

            conn.setAutoCommit(false);
            stmt.executeUpdate(
                    "CREATE TABLE " + AbstractSQLGenerator.escapeIdentifier(tableName) + " (col1 int primary key)");
            for (int i = 0; i < 80; i++) {
                stmt.executeUpdate("INSERT INTO " + AbstractSQLGenerator.escapeIdentifier(tableName) + "(col1) values ("
                        + i + ")");
            }
            conn.commit();
            try {
                stmt.execute("SELECT x1.col1 as foo, x2.col1 as bar, x1.col1 as eeep FROM "
                        + AbstractSQLGenerator.escapeIdentifier(tableName) + " as x1, "
                        + AbstractSQLGenerator.escapeIdentifier(tableName)
                        + " as x2; RAISERROR ('Oops', 21, 42) WITH LOG");
            } catch (SQLException e) {
                assertEquals(e.getMessage(), TestResource.getResource("R_connectionReset"),
                        TestResource.getResource("R_unknownException"));
            }
            assertEquals(conn.isValid(5), false, TestResource.getResource("R_deadConnection"));
        } catch (Exception e) {
            fail(TestResource.getResource("R_unexpectedErrorMessage") + e.toString());
        } finally {
            if (null != tableName) {
                try (SQLServerConnection conn = (SQLServerConnection) DriverManager
                        .getConnection(connectionString + ";responseBuffering=adaptive");
                        Statement stmt = conn.createStatement()) {
                    stmt.execute("drop table " + AbstractSQLGenerator.escapeIdentifier(tableName));
                }
            }
        }
    }

    @Test
    public void testClientConnectionId() throws Exception {
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(connectionString)) {
            assertTrue(conn.getClientConnectionId() != null, TestResource.getResource("R_clientConnectionIdNull"));
            conn.close();
            try {
                // Call getClientConnectionId on a closed connection, should raise exception
                conn.getClientConnectionId();
                throw new Exception(TestResource.getResource("R_noExceptionClosedConnection"));
            } catch (SQLException e) {
                assertEquals(e.getMessage(), TestResource.getResource("R_connectionIsClosed"),
                        TestResource.getResource("R_wrongExceptionMessage"));
            }
        }

        // Wrong database, ClientConnectionId should be available in error message
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager
                .getConnection(connectionString + ";databaseName=" + RandomUtil.getIdentifierForDB("DataBase") + ";")) {
            conn.close();

        } catch (SQLException e) {
            assertTrue(e.getMessage().indexOf("ClientConnectionId") != -1,
                    TestResource.getResource("R_unexpectedWrongDB"));
        }

        // Nonexist host, ClientConnectionId should not be available in error message
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(
                connectionString + ";instanceName=" + RandomUtil.getIdentifier("Instance") + ";logintimeout=5;")) {
            conn.close();

        } catch (SQLException e) {
            assertEquals(false, e.getMessage().indexOf("ClientConnectionId") != -1,
                    TestResource.getResource("R_unexpectedWrongHost"));
        }
    }

    @Test
    public void testIncorrectDatabase() throws SQLException {
        long timerStart = 0;
        long timerEnd = 0;
        final long milsecs = threshHoldForNoRetryInMilliseconds;
        try {
            SQLServerDataSource ds = new SQLServerDataSource();
            ds.setURL(connectionString);
            ds.setLoginTimeout(loginTimeOutInSeconds);
            ds.setDatabaseName(RandomUtil.getIdentifier("DataBase"));
            timerStart = System.currentTimeMillis();
            try (Connection con = ds.getConnection()) {

                long timeDiff = timerEnd - timerStart;
                assertTrue(con == null, TestResource.getResource("R_shouldNotConnect"));

                MessageFormat form = new MessageFormat(TestResource.getResource("R_exitedMoreSeconds"));
                Object[] msgArgs = {milsecs / 1000};
                assertTrue(timeDiff <= milsecs, form.format(msgArgs));
            }
        } catch (Exception e) {
            assertTrue(e.getMessage().contains(TestResource.getResource("R_cannotOpenDatabase")));
            timerEnd = System.currentTimeMillis();
        }
    }

    @Test
    public void testIncorrectUserName() throws SQLException {
        long timerStart = 0;
        long timerEnd = 0;
        final long milsecs = threshHoldForNoRetryInMilliseconds;
        try {
            SQLServerDataSource ds = new SQLServerDataSource();
            ds.setURL(connectionString);
            ds.setLoginTimeout(loginTimeOutInSeconds);
            ds.setUser(RandomUtil.getIdentifier("User"));
            timerStart = System.currentTimeMillis();
            try (Connection con = ds.getConnection()) {
                long timeDiff = timerEnd - timerStart;
                assertTrue(con == null, TestResource.getResource("R_shouldNotConnect"));
                MessageFormat form = new MessageFormat(TestResource.getResource("R_exitedMoreSeconds"));
                Object[] msgArgs = {milsecs / 1000};
                assertTrue(timeDiff <= milsecs, form.format(msgArgs));
            }
        } catch (Exception e) {
            assertTrue(e.getMessage().contains(TestResource.getResource("R_loginFailed")));
            timerEnd = System.currentTimeMillis();
        }
    }

    @Test
    public void testIncorrectPassword() throws SQLException {
        long timerStart = 0;
        long timerEnd = 0;
        final long milsecs = threshHoldForNoRetryInMilliseconds;
        try {
            SQLServerDataSource ds = new SQLServerDataSource();
            ds.setURL(connectionString);
            ds.setLoginTimeout(loginTimeOutInSeconds);
            ds.setPassword(RandomUtil.getIdentifier("Password"));
            timerStart = System.currentTimeMillis();
            try (Connection con = ds.getConnection()) {
                long timeDiff = timerEnd - timerStart;
                assertTrue(con == null, TestResource.getResource("R_shouldNotConnect"));
                MessageFormat form = new MessageFormat(TestResource.getResource("R_exitedMoreSeconds"));
                Object[] msgArgs = {milsecs / 1000};
                assertTrue(timeDiff <= milsecs, form.format(msgArgs));
            }
        } catch (Exception e) {
            assertTrue(e.getMessage().contains(TestResource.getResource("R_loginFailed")));
            timerEnd = System.currentTimeMillis();
        }
    }

    @Test
    public void testInvalidCombination() throws SQLException {
        long timerStart = 0;
        long timerEnd = 0;
        final long milsecs = threshHoldForNoRetryInMilliseconds;
        try {
            SQLServerDataSource ds = new SQLServerDataSource();
            ds.setURL(connectionString);
            ds.setLoginTimeout(loginTimeOutInSeconds);
            ds.setMultiSubnetFailover(true);
            ds.setFailoverPartner(RandomUtil.getIdentifier("FailoverPartner"));
            timerStart = System.currentTimeMillis();
            try (Connection con = ds.getConnection()) {
                long timeDiff = timerEnd - timerStart;
                assertTrue(con == null, TestResource.getResource("R_shouldNotConnect"));
                MessageFormat form = new MessageFormat(TestResource.getResource("R_exitedMoreSeconds"));
                Object[] msgArgs = {milsecs / 1000};
                assertTrue(timeDiff <= milsecs, form.format(msgArgs));
            }
        } catch (Exception e) {
            assertTrue(e.getMessage().contains(TestResource.getResource("R_connectMirrored")));
            timerEnd = System.currentTimeMillis();
        }
    }

    @Test
    @Tag("slow")
    public void testIncorrectDatabaseWithFailoverPartner() throws SQLException {
        long timerStart = 0;
        long timerEnd = 0;
        try {
            SQLServerDataSource ds = new SQLServerDataSource();
            ds.setURL(connectionString);
            ds.setLoginTimeout(loginTimeOutInSeconds);
            ds.setDatabaseName(RandomUtil.getIdentifierForDB("DB"));
            ds.setFailoverPartner(RandomUtil.getIdentifier("FailoverPartner"));
            timerStart = System.currentTimeMillis();
            try (Connection con = ds.getConnection()) {
                long timeDiff = timerEnd - timerStart;
                assertTrue(con == null, TestResource.getResource("R_shouldNotConnect"));
                MessageFormat form = new MessageFormat(TestResource.getResource("R_exitedLessSeconds"));
                Object[] msgArgs = {loginTimeOutInSeconds - 1};
                assertTrue(timeDiff >= ((loginTimeOutInSeconds - 1) * 1000), form.format(msgArgs));
            }
        } catch (Exception e) {
            timerEnd = System.currentTimeMillis();
        }
    }

    @Test
    public void testAbortBadParam() throws SQLException {
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(connectionString)) {
            try {
                conn.abort(null);
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains(TestResource.getResource("R_invalidArgumentExecutor")));
            }
        }
    }

    @Test
    public void testAbort() throws SQLException {
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(connectionString)) {
            Executor executor = Executors.newFixedThreadPool(2);
            conn.abort(executor);
        }
    }

    @Test
    public void testSetSchema() throws SQLException {
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(connectionString)) {
            conn.setSchema(RandomUtil.getIdentifier("schema"));
        }
    }

    @Test
    public void testGetSchema() throws SQLException {
        try (SQLServerConnection conn = (SQLServerConnection) DriverManager.getConnection(connectionString)) {
            conn.getSchema();
        }
    }

    static Boolean isInterrupted = false;

    /**
     * Test thread's interrupt status is not cleared.
     * 
     * @throws InterruptedException
     */
    @Test
    @Tag("slow")
    public void testThreadInterruptedStatus() throws InterruptedException {
        Runnable runnable = new Runnable() {
            public void run() {
                SQLServerDataSource ds = new SQLServerDataSource();

                ds.setURL(connectionString);
                ds.setServerName("invalidServerName" + UUID.randomUUID());
                ds.setLoginTimeout(5);

                try (Connection con = ds.getConnection()) {} catch (SQLException e) {
                    isInterrupted = Thread.currentThread().isInterrupted();
                }
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(1);
        Future<?> future = executor.submit(runnable);

        Thread.sleep(1000);

        // interrupt the thread in the Runnable
        future.cancel(true);

        Thread.sleep(8000);

        executor.shutdownNow();

        assertTrue(isInterrupted, TestResource.getResource("R_threadInterruptNotSet"));
    }
}
