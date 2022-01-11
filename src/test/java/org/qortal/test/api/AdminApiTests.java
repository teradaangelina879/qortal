package org.qortal.test.api;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.qortal.api.resource.AdminResource;
import org.qortal.repository.DataException;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;

public class AdminApiTests extends ApiCommon {

	private AdminResource adminResource;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Before
	public void buildResource() {
		this.adminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class);
	}

	@Test
	public void testInfo() {
		assertNotNull(this.adminResource.info());
	}

	@Test
	public void testSummary() {
		assertNotNull(this.adminResource.summary("testApiKey"));
	}

	@Test
	public void testGetMintingAccounts() {
		assertNotNull(this.adminResource.getMintingAccounts());
	}

}
