package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.repository.DataException;
import org.qortal.storage.DataFile;
import org.qortal.test.common.Common;

import static org.junit.Assert.*;

public class DataTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSplitAndJoin() {
		String dummyDataString = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
		DataFile dataFile = new DataFile(dummyDataString.getBytes());
		assertTrue(dataFile.exists());
		assertEquals(62, dataFile.size());
		assertEquals("3eyjYjturyVe61grRX42bprGr3Cvw6ehTy4iknVnosDj", dataFile.base58Digest());

		// Split into 7 chunks, each 10 bytes long
		dataFile.split(10);
		assertEquals(7, dataFile.chunkCount());

		// Delete the original file
		dataFile.delete();
		assertFalse(dataFile.exists());
		assertEquals(0, dataFile.size());

		// Now rebuild the original file from the chunks
		assertEquals(7, dataFile.chunkCount());
		dataFile.join();

		// Validate that the original file is intact
		assertTrue(dataFile.exists());
		assertEquals(62, dataFile.size());
		assertEquals("3eyjYjturyVe61grRX42bprGr3Cvw6ehTy4iknVnosDj", dataFile.base58Digest());
	}

}
