package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;

import static org.junit.Assert.*;

import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RepositoryTests extends Common {

	private static final Logger LOGGER = LogManager.getLogger(RepositoryTests.class);

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testGetRepository() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository);
		}
	}

	@Test
	public void testMultipleInstances() throws DataException {
		int n_instances = 5;
		Repository[] repositories = new Repository[n_instances];

		for (int i = 0; i < n_instances; ++i) {
			repositories[i] = RepositoryManager.getRepository();
			assertNotNull(repositories[i]);
		}

		for (int i = 0; i < n_instances; ++i) {
			repositories[i].close();
			repositories[i] = null;
		}
	}

	@Test
	public void testAccessAfterClose() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository);

			repository.close();

			try {
				repository.discardChanges();
				fail();
			} catch (NullPointerException | DataException e) {
			}

			LOGGER.warn("Expect \"repository already closed\" complaint below");
		}
	}

	@Test
	public void testDeadlock() throws DataException {
		// Open connection 1
		try (final Repository repository1 = RepositoryManager.getRepository()) {

			// Do a database 'read'
			Account account1 = Common.getTestAccount(repository1, "alice");
			account1.getLastReference();

			// Open connection 2
			try (final Repository repository2 = RepositoryManager.getRepository()) {
				// Update account in 2
				Account account2 = Common.getTestAccount(repository2, "alice");
				account2.setConfirmedBalance(Asset.QORT, 1234L);
				repository2.saveChanges();
			}

			repository1.discardChanges();

			// Update account in 1
			account1.setConfirmedBalance(Asset.QORT, 5678L);
			repository1.saveChanges();
		}
	}

	/** Check that the <i>sub-query</i> used to fetch highest block height is optimized by HSQLDB. */
	@Test
	public void testBlockHeightSpeed() throws DataException, SQLException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Mint some blocks
			System.out.println("Minting test blocks - should take approx. 30 seconds...");
			for (int i = 0; i < 30000; ++i)
				BlockUtils.mintBlock(repository);

			final HSQLDBRepository hsqldb = (HSQLDBRepository) repository;

			// Too slow:
			testSql(hsqldb, "SELECT IFNULL(MAX(height), 0) + 1 FROM Blocks", false);

			// Fast but if there are no rows, then no result is returned, which causes some triggers to fail:
			testSql(hsqldb, "SELECT IFNULL(height, 0) + 1 FROM (SELECT height FROM Blocks ORDER BY height DESC LIMIT 1)", true);

			// Too slow:
			testSql(hsqldb, "SELECT COUNT(*) + 1 FROM Blocks", false);

			// 2-stage, using cached value:
			hsqldb.prepareStatement("DROP TABLE IF EXISTS TestNextBlockHeight").execute();
			hsqldb.prepareStatement("CREATE TABLE TestNextBlockHeight (height INT NOT NULL)").execute();
			hsqldb.prepareStatement("INSERT INTO TestNextBlockHeight VALUES (SELECT IFNULL(MAX(height), 0) + 1 FROM Blocks)").execute();

			// 1: Check fetching cached next block height is fast:
			testSql(hsqldb, "SELECT height from TestNextBlockHeight", true);

			// 2: Check updating NextBlockHeight (typically called via trigger) is fast:
			testSql(hsqldb, "UPDATE TestNextBlockHeight SET height = (SELECT height FROM Blocks ORDER BY height DESC LIMIT 1)", true);
		}
	}

	/** Test proper action of interrupt inside an HSQLDB statement. */
	@Test
	public void testInterrupt() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final Thread testThread = Thread.currentThread();
			System.out.println(String.format("Thread ID: %s", testThread.getId()));

			// Queue interrupt
			ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
			executor.schedule(() -> testThread.interrupt(), 1000L, TimeUnit.MILLISECONDS);

			// Set rollback on interrupt
			@SuppressWarnings("resource")
			final HSQLDBRepository hsqldb = (HSQLDBRepository) repository;
			hsqldb.prepareStatement("SET DATABASE TRANSACTION ROLLBACK ON INTERRUPT TRUE").execute();

			// Create SQL procedure that calls hsqldbSleep() to block HSQLDB so we can interrupt()
			hsqldb.prepareStatement("CREATE PROCEDURE sleep(IN millis INT) LANGUAGE JAVA DETERMINISTIC NO SQL EXTERNAL NAME 'CLASSPATH:org.qortal.test.RepositoryTests.hsqldbSleep'").execute();

			// Execute long-running statement
			hsqldb.prepareStatement("CALL sleep(2000)").execute();

			if (!testThread.isInterrupted())
				// We should not reach here
				fail("Interrupt was swallowed");
		} catch (DataException | SQLException e) {
			fail("DataException during blocked statement");
		}
	}

	public static void hsqldbSleep(int millis) throws SQLException {
		System.out.println(String.format("HSQLDB sleep() thread ID: %s", Thread.currentThread().getId()));

		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void testSql(HSQLDBRepository hsqldb, String sql, boolean isFast) throws DataException, SQLException {
		// Execute query to prime caches
		hsqldb.prepareStatement(sql).execute();

		// Execute again for a slightly more accurate timing
		final long start = System.currentTimeMillis();
		hsqldb.prepareStatement(sql).execute();

		final long executionTime = System.currentTimeMillis() - start;
		System.out.println(String.format("%s: [%d ms] SQL: %s", (isFast ? "fast": "slow"), executionTime, sql));

		final long threshold = 3; // ms
		assertTrue( !isFast || executionTime < threshold);
	}

}
