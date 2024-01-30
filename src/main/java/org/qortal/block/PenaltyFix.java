package org.qortal.block;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.qortal.api.model.AccountPenaltyStats;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountPenaltyData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Base58;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public final class PenaltyFix {

	private static final Logger LOGGER = LogManager.getLogger(PenaltyFix.class);

	private static final String PENALTY_FIX_SOURCE = "penalty-fix.json";
	private static final String PENALTY_FIX_HASH = BlockChain.getInstance().getPenaltyFixHash();
	private static final List<AccountPenaltyData> penalties = accountsPenaltyFix();

	private PenaltyFix() {
		/* Do not instantiate */
	}

	@SuppressWarnings("unchecked")
	private static List<AccountPenaltyData> accountsPenaltyFix() {
		Unmarshaller unmarshaller;

		try {
			// Create JAXB context aware of classes we need to unmarshal
			JAXBContext pf = JAXBContextFactory.createContext(new Class[] {
					AccountPenaltyData.class
			}, null);

			// Create unmarshaller
			unmarshaller = pf.createUnmarshaller();

			// Set the unmarshaller media type to JSON
			unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell unmarshaller that there's no JSON root element in the JSON input
			unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);
		} catch (JAXBException e) {
			String message = "Failed to setup unmarshaller for penalty fix";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}

		ClassLoader classLoader = BlockChain.class.getClassLoader();
		InputStream pfIn = classLoader.getResourceAsStream(PENALTY_FIX_SOURCE);
		StreamSource pfSource = new StreamSource(pfIn);

		try  {
			// Attempt to unmarshal JSON stream to BlockChain config
			return (List<AccountPenaltyData>) unmarshaller.unmarshal(pfSource, AccountPenaltyData.class).getValue();
		} catch (UnmarshalException e) {
			String message = "Failed to parse penalty fix";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		} catch (JAXBException e) {
			String message = "Unexpected JAXB issue while processing penalty fix";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}
	}

	public static void processPenaltiesFix(Block block) throws DataException {

		// Create reverse penalties
		List<AccountPenaltyData> reversePenalties = penalties.stream()
				.map(penalty -> new AccountPenaltyData(penalty.getAddress(), 0 +5000000))
				.collect(Collectors.toList());

		Set<AccountPenaltyData> penaltiesFix = new HashSet<AccountPenaltyData>(reversePenalties);

		final String PENALTY_FIX_HASH_VERIFY = getHash(penaltiesFix.stream().map(p -> p.getAddress()).collect(Collectors.toList()));

		// Verify if we are on same penalty hash
		if (PENALTY_FIX_HASH.equals(PENALTY_FIX_HASH_VERIFY)) {
			LOGGER.info("Verify hash passed! Running process penalty fix - this will take a while...");
			logPenaltyStats(block.repository);
			long startTime = System.currentTimeMillis();
			block.repository.getAccountRepository().updateBlocksMintedPenalties(penaltiesFix);
			long totalTime = System.currentTimeMillis() - startTime;
			LOGGER.info("{} penalty addresses processed. Total time taken: {} seconds", reversePenalties.size(), (int)(totalTime / 1000.0f));
			logPenaltyStats(block.repository);

			int updatedCount = updateAccountLevels(block.repository, penaltiesFix);
			LOGGER.info("Account levels updated for {} penalty addresses", updatedCount);
		} else {
			LOGGER.info("Verify hash failed! Stopping process penalty fix!");
		}
	}

	public static void orphanPenaltiesFix(Block block) throws DataException {

		// Create inverse penalties
		List<AccountPenaltyData> inversePenalties = penalties.stream()
				.map(penalty -> new AccountPenaltyData(penalty.getAddress(), 0 -5000000))
				.collect(Collectors.toList());

		Set<AccountPenaltyData> penaltiesFix = new HashSet<AccountPenaltyData>(inversePenalties);

		final String PENALTY_FIX_HASH_VERIFY = getHash(penaltiesFix.stream().map(p -> p.getAddress()).collect(Collectors.toList()));

		// Verify if we are on same penalty hash
		if (PENALTY_FIX_HASH.equals(PENALTY_FIX_HASH_VERIFY)) {
			LOGGER.info("Verify hash passed! Running orphan penalty fix - this will take a while...");
			logPenaltyStats(block.repository);
			long startTime = System.currentTimeMillis();
			block.repository.getAccountRepository().updateBlocksMintedPenalties(penaltiesFix);
			long totalTime = System.currentTimeMillis() - startTime;
			LOGGER.info("{} penalty addresses processed. Total time taken: {} seconds", inversePenalties.size(), (int)(totalTime / 1000.0f));
			logPenaltyStats(block.repository);

			int updatedCount = updateAccountLevels(block.repository, penaltiesFix);
			LOGGER.info("Account levels updated for {} penalty addresses", updatedCount);
		} else {
			LOGGER.info("Verify hash failed! Stopping orphan penalty fix!");
		}
	}

	private static int updateAccountLevels(Repository repository, Set<AccountPenaltyData> accountPenalties) throws DataException {
		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;

		int updatedCount = 0;

		for (AccountPenaltyData penaltyData : accountPenalties) {
			AccountData accountData = repository.getAccountRepository().getAccount(penaltyData.getAddress());
			final int effectiveBlocksMinted = accountData.getBlocksMinted() + accountData.getBlocksMintedAdjustment() + accountData.getBlocksMintedPenalty();

			// Shortcut for penalties
			if (effectiveBlocksMinted < 0) {
				accountData.setLevel(0);
				repository.getAccountRepository().setLevel(accountData);
				updatedCount++;
				LOGGER.trace(() -> String.format("Block minter %s dropped to level %d", accountData.getAddress(), accountData.getLevel()));
				continue;
			}

			for (int newLevel = maximumLevel; newLevel >= 0; --newLevel) {
				if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
					accountData.setLevel(newLevel);
					repository.getAccountRepository().setLevel(accountData);
					updatedCount++;
					LOGGER.trace(() -> String.format("Block minter %s increased to level %d", accountData.getAddress(), accountData.getLevel()));
					break;
				}
			}
		}
		return updatedCount;
	}

	private static void logPenaltyStats(Repository repository) {
		try {
			LOGGER.info(getPenaltyStats(repository));
		} catch (DataException e) {}
	}

	private static AccountPenaltyStats getPenaltyStats(Repository repository) throws DataException {
		List<AccountData> accounts = repository.getAccountRepository().getPenaltyAccounts();
		return AccountPenaltyStats.fromAccounts(accounts);
	}

	public static String getHash(List<String> penaltyAddresses) {
		if (penaltyAddresses == null || penaltyAddresses.isEmpty()) {
			return null;
		}

		Collections.sort(penaltyAddresses);
		return Base58.encode(Crypto.digest(StringUtils.join(penaltyAddresses).getBytes(StandardCharsets.UTF_8)));
	}
}