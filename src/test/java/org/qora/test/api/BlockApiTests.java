package org.qora.test.api;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.qora.api.resource.BlocksResource;
import org.qora.test.common.ApiCommon;

public class BlockApiTests extends ApiCommon {

	private BlocksResource blocksResource;

	@Before
	public void buildResource() {
		this.blocksResource = (BlocksResource) ApiCommon.buildResource(BlocksResource.class);
	}

	@Test
	public void test() {
		assertNotNull(this.blocksResource);
	}

	@Test
	public void testGetBlockForgers() {
		List<String> addresses = Arrays.asList(aliceAddress, aliceAddress);

		assertNotNull(this.blocksResource.getBlockMinters(Collections.emptyList(), null, null, null));
		assertNotNull(this.blocksResource.getBlockMinters(addresses, null, null, null));
		assertNotNull(this.blocksResource.getBlockMinters(Collections.emptyList(), 1, 1, true));
		assertNotNull(this.blocksResource.getBlockMinters(addresses, 1, 1, true));
	}

	@Test
	public void testGetBlocksByForger() {
		assertNotNull(this.blocksResource.getBlocksByMinter(aliceAddress, null, null, null));
		assertNotNull(this.blocksResource.getBlocksByMinter(aliceAddress, 1, 1, true));
	}

}
