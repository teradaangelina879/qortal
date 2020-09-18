package org.qortal.crosschain;

import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public interface ACCT {

	public byte[] getCodeBytesHash();

	public ForeignBlockchain getBlockchain();

	public CrossChainTradeData populateTradeData(Repository repository, ATData atData) throws DataException;

}
