package org.qora.settings;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.transform.stream.StreamSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.exceptions.XMLMarshalException;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.qora.block.BlockChain;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class Settings {

	private static final int MAINNET_LISTEN_PORT = 12392;
	private static final int TESTNET_LISTEN_PORT = 62392;

	private static final int MAINNET_API_PORT = 12391;
	private static final int TESTNET_API_PORT = 62391;

	private static final int MAINNET_UI_PORT = 12390;
	private static final int TESTNET_UI_PORT = 62390;

	private static final Logger LOGGER = LogManager.getLogger(Settings.class);
	private static final String SETTINGS_FILENAME = "settings.json";

	// Properties
	private static Settings instance;

	// Settings, and other config files
	private String userPath;

	// Common to all networking (UI/API/P2P)
	private String bindAddress = "::"; // Use IPv6 wildcard to listen on all local addresses

	// Node management UI
	private boolean uiEnabled = true;
	private Integer uiPort;
	private String[] uiWhitelist = new String[] {
		"::1", "127.0.0.1"
	};

	// API-related
	private boolean apiEnabled = true;
	private Integer apiPort;
	private String[] apiWhitelist = new String[] {
		"::1", "127.0.0.1"
	};
	private Boolean apiRestricted;
	private boolean apiLoggingEnabled = false;

	// Specific to this node
	private boolean wipeUnconfirmedOnStart = false;
	/** Maximum number of unconfirmed transactions allowed per account */
	private int maxUnconfirmedPerAccount = 100;
	/** Max milliseconds into future for accepting new, unconfirmed transactions */
	private int maxTransactionTimestampFuture = 24 * 60 * 60 * 1000; // milliseconds
	/** Whether we check, fetch and install auto-updates */
	private boolean autoUpdateEnabled = true;

	// Peer-to-peer related
	private boolean isTestNet = false;
	/** Port number for inbound peer-to-peer connections. */
	private Integer listenPort;
	/** Minimum number of peers to allow block generation / synchronization. */
	private int minBlockchainPeers = 5;
	/** Target number of outbound connections to peers we should make. */
	private int minOutboundPeers = 20;
	/** Maximum number of peer connections we allow. */
	private int maxPeers = 50;

	// Which blockchains this node is running
	private String blockchainConfig = null; // use default from resources
	private boolean useBitcoinTestNet = false;

	// Repository related
	/** Queries that take longer than this are logged. (milliseconds) */
	private Long slowQueryThreshold = null;
	/** Repository storage path. */
	private String repositoryPath = "db";

	// Auto-update sources
	private String[] autoUpdateRepos = new String[] {
		"https://github.com/catbref/qora-core/raw/%s/qora-core.jar",
		"https://raw.githubusercontent.com@151.101.16.133/catbref/qora-core/%s/qora-core.jar"
	};

	/** Array of NTP server hostnames. */
	private String[] ntpServers = new String[] {
		"pool.ntp.org",
		"0.pool.ntp.org",
		"1.pool.ntp.org",
		"2.pool.ntp.org",
		"3.pool.ntp.org",
		"cn.pool.ntp.org",
		"0.cn.pool.ntp.org",
		"1.cn.pool.ntp.org",
		"2.cn.pool.ntp.org",
		"3.cn.pool.ntp.org"
	};
	/** Additional offset added to values returned by NTP.getTime() */
	private long testNtpOffset = 0;

	// Constructors

	private Settings() {
	}

	// Other methods

	public static synchronized Settings getInstance() {
		if (instance == null)
			fileInstance(SETTINGS_FILENAME);

		return instance;
	}

	/**
	 * Parse settings from given file.
	 * <p>
	 * Throws <tt>RuntimeException</tt> with <tt>UnmarshalException</tt> as cause if settings file could not be parsed.
	 * <p>
	 * We use <tt>RuntimeException</tt> because it can be caught first caller of {@link #getInstance()} above,
	 * but it's not necessary to surround later {@link #getInstance()} calls
	 * with <tt>try-catch</tt> as they should be read-only.
	 *
	 * @param filename
	 * @throws RuntimeException with UnmarshalException as cause if settings file could not be parsed
	 * @throws RuntimeException with FileNotFoundException as cause if settings file could not be found/opened
	 * @throws RuntimeException with JAXBException as cause if some unexpected JAXB-related error occurred
	 * @throws RuntimeException with IOException as cause if some unexpected I/O-related error occurred
	 */
	public static void fileInstance(String filename) {
		JAXBContext jc;
		Unmarshaller unmarshaller;

		try {
			// Create JAXB context aware of Settings
			jc = JAXBContextFactory.createContext(new Class[] {
				Settings.class
			}, null);

			// Create unmarshaller
			unmarshaller = jc.createUnmarshaller();

			// Set the unmarshaller media type to JSON
			unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell unmarshaller that there's no JSON root element in the JSON input
			unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);
		} catch (JAXBException e) {
			String message = "Failed to setup unmarshaller to process settings file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}

		Settings settings = null;
		String path = "";

		do {
			LOGGER.info("Using settings file: " + path + filename);

			// Create the StreamSource by creating Reader to the JSON input
			try (Reader settingsReader = new FileReader(path + filename)) {
				StreamSource json = new StreamSource(settingsReader);

				// Attempt to unmarshal JSON stream to Settings
				settings = unmarshaller.unmarshal(json, Settings.class).getValue();
			} catch (FileNotFoundException e) {
				String message = "Settings file not found: " + path + filename;
				LOGGER.error(message, e);
				throw new RuntimeException(message, e);
			} catch (UnmarshalException e) {
				Throwable linkedException = e.getLinkedException();
				if (linkedException instanceof XMLMarshalException) {
					String message = ((XMLMarshalException) linkedException).getInternalException().getLocalizedMessage();
					LOGGER.error(message);
					throw new RuntimeException(message, e);
				}

				String message = "Failed to parse settings file";
				LOGGER.error(message, e);
				throw new RuntimeException(message, e);
			} catch (JAXBException e) {
				String message = "Unexpected JAXB issue while processing settings file";
				LOGGER.error(message, e);
				throw new RuntimeException(message, e);
			} catch (IOException e) {
				String message = "Unexpected I/O issue while processing settings file";
				LOGGER.error(message, e);
				throw new RuntimeException(message, e);
			}

			if (settings.userPath != null) {
				// Adjust filename and go round again
				path = settings.userPath;

				// Add trailing directory separator if needed
				if (!path.endsWith(File.separator))
					path += File.separator;
			}
		} while (settings.userPath != null);

		// Validate settings
		settings.validate();

		// Minor fix-up
		settings.userPath = path;

		// Successfully read settings now in effect
		instance = settings;

		// Now read blockchain config
		BlockChain.fileInstance(settings.getUserPath(), settings.getBlockchainConfig());
	}

	public static void throwValidationError(String message) {
		throw new RuntimeException(message, new UnmarshalException(message));
	}

	private void validate() {
		// Validation goes here
		if (this.minBlockchainPeers < 1)
			throwValidationError("minBlockchainPeers must be at least 1");
	}

	// Getters / setters

	public String getUserPath() {
		return this.userPath;
	}

	public boolean isUiEnabled() {
		return this.uiEnabled;
	}

	public int getUiPort() {
		if (this.uiPort != null)
			return this.uiPort;

		return this.isTestNet ? TESTNET_UI_PORT : MAINNET_UI_PORT;
	}

	public String[] getUiWhitelist() {
		return this.uiWhitelist;
	}

	public boolean isApiEnabled() {
		return this.apiEnabled;
	}

	public int getApiPort() {
		if (this.apiPort != null)
			return this.apiPort;

		return this.isTestNet ? TESTNET_API_PORT : MAINNET_API_PORT;
	}

	public String[] getApiWhitelist() {
		return this.apiWhitelist;
	}

	public boolean isApiRestricted() {
		// Explicitly set value takes precedence
		if (this.apiRestricted != null)
			return this.apiRestricted;

		// Not set in config file, so restrict if not testnet
		return !BlockChain.getInstance().isTestChain();
	}

	public boolean isApiLoggingEnabled() {
		return this.apiLoggingEnabled;
	}

	public boolean getWipeUnconfirmedOnStart() {
		return this.wipeUnconfirmedOnStart;
	}

	public int getMaxUnconfirmedPerAccount() {
		return this.maxUnconfirmedPerAccount;
	}

	public int getMaxTransactionTimestampFuture() {
		return this.maxTransactionTimestampFuture;
	}

	public boolean isTestNet() {
		return this.isTestNet;
	}

	public int getListenPort() {
		if (this.listenPort != null)
			return this.listenPort;

		return this.isTestNet ? TESTNET_LISTEN_PORT : MAINNET_LISTEN_PORT;
	}

	public int getDefaultListenPort() {
		return this.isTestNet ? TESTNET_LISTEN_PORT : MAINNET_LISTEN_PORT;
	}

	public String getBindAddress() {
		return this.bindAddress;
	}

	public int getMinBlockchainPeers() {
		return this.minBlockchainPeers;
	}

	public int getMinOutboundPeers() {
		return this.minOutboundPeers;
	}

	public int getMaxPeers() {
		return this.maxPeers;
	}

	public String getBlockchainConfig() {
		return this.blockchainConfig;
	}

	public boolean useBitcoinTestNet() {
		return this.useBitcoinTestNet;
	}

	public Long getSlowQueryThreshold() {
		return this.slowQueryThreshold;
	}

	public String getRepositoryPath() {
		return this.repositoryPath;
	}

	public boolean isAutoUpdateEnabled() {
		return this.autoUpdateEnabled;
	}

	public String[] getAutoUpdateRepos() {
		return this.autoUpdateRepos;
	}

	public String[] getNtpServers() {
		return this.ntpServers;
	}

	public long getTestNtpOffset() {
		return this.testNtpOffset;
	}

}
