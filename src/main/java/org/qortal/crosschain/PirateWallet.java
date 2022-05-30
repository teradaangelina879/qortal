package org.qortal.crosschain;

import com.google.common.io.Resources;
import com.rust.litewalletjni.LiteWalletJni;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.DecoderException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

import static org.qortal.crosschain.PirateChain.DEFAULT_BIRTHDAY;

public class PirateWallet {

    protected static final Logger LOGGER = LogManager.getLogger(PirateWallet.class);

    private byte[] entropyBytes;
    private final boolean isNullSeedWallet;
    private String seedPhrase;
    private boolean ready = false;

    private final String params;
    private final String saplingOutput64;
    private final String saplingSpend64;

    private final static String SERVER_URI = "https://lightd.pirate.black:443/";
    private final static String COIN_PARAMS_RESOURCE = "piratechain/coinparams.json";
    private final static String SAPLING_OUTPUT_RESOURCE = "piratechain/saplingoutput_base64";
    private final static String SAPLING_SPEND_RESOURCE = "piratechain/saplingspend_base64";

    public PirateWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
        this.entropyBytes = entropyBytes;
        this.isNullSeedWallet = isNullSeedWallet;

        final URL paramsUrl = Resources.getResource(COIN_PARAMS_RESOURCE);
        this.params = Resources.toString(paramsUrl, StandardCharsets.UTF_8);

        final URL saplingOutput64Url = Resources.getResource(SAPLING_OUTPUT_RESOURCE);
        this.saplingOutput64 = Resources.toString(saplingOutput64Url, StandardCharsets.ISO_8859_1);

        final URL saplingSpend64Url = Resources.getResource(SAPLING_SPEND_RESOURCE);
        this.saplingSpend64 = Resources.toString(saplingSpend64Url, StandardCharsets.ISO_8859_1);

        this.ready = this.initialize();
    }

    private boolean initialize() {
        try {
            LiteWalletJni.initlogging();

            if (this.entropyBytes == null) {
                return false;
            }

            // Pirate library uses base64 encoding
            String entropy64 = Base64.toBase64String(this.entropyBytes);

            // Derive seed phrase from entropy bytes
            String inputSeedResponse = LiteWalletJni.getseedphrasefromentropyb64(entropy64);
            JSONObject inputSeedJson =  new JSONObject(inputSeedResponse);
            String inputSeedPhrase = null;
            if (inputSeedJson.has("seedPhrase")) {
                inputSeedPhrase = inputSeedJson.getString("seedPhrase");
            }

            String wallet = this.load();
            if (wallet == null) {
                // Wallet doesn't exist, so create a new one

                int birthday = DEFAULT_BIRTHDAY;
                if (this.isNullSeedWallet) {
                    try {
                        // Attempt to set birthday to the current block for null seed wallets
                        birthday = PirateChain.getInstance().blockchainProvider.getCurrentHeight();
                    }
                    catch (ForeignBlockchainException e) {
                        // Use the default height
                    }
                }

                // Initialize new wallet
                String birthdayString = String.format("%d", birthday);
                String outputSeedResponse = LiteWalletJni.initfromseed(SERVER_URI, this.params, inputSeedPhrase, birthdayString, this.saplingOutput64, this.saplingSpend64); // Thread-safe.
                JSONObject outputSeedJson =  new JSONObject(outputSeedResponse);
                String outputSeedPhrase = null;
                if (outputSeedJson.has("seed")) {
                    outputSeedPhrase = outputSeedJson.getString("seed");
                }

                // Ensure seed phrase in response matches supplied seed phrase
                if (inputSeedPhrase == null || !Objects.equals(inputSeedPhrase, outputSeedPhrase)) {
                    LOGGER.info("Unable to initialize Pirate Chain wallet: seed phrases do not match, or are null");
                    return false;
                }

                this.seedPhrase = outputSeedPhrase;

            } else {
                // Restore existing wallet
                LiteWalletJni.initfromb64(SERVER_URI, params, wallet, saplingOutput64, saplingSpend64);
                this.seedPhrase = inputSeedPhrase;
            }

            // Check that we're able to communicate with the library
            Integer ourHeight = this.getHeight();
            return (ourHeight != null && ourHeight > 0);

        } catch (IOException | JSONException | UnsatisfiedLinkError e) {
            LOGGER.info("Unable to initialize Pirate Chain wallet: {}", e.getMessage());
        }

        return false;
    }

    public boolean isReady() {
        return this.ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean entropyBytesEqual(byte[] testEntropyBytes) {
        return Arrays.equals(testEntropyBytes, this.entropyBytes);
    }

    private void encrypt() {
        if (this.isEncrypted()) {
            // Nothing to do
            return;
        }

        String encryptionKey = this.getEncryptionKey();
        if (encryptionKey == null) {
            // Can't encrypt without a key
            return;
        }

        this.doEncrypt(encryptionKey);
    }

    private void decrypt() {
        if (!this.isEncrypted()) {
            // Nothing to do
            return;
        }

        String encryptionKey = this.getEncryptionKey();
        if (encryptionKey == null) {
            // Can't encrypt without a key
            return;
        }

        this.doDecrypt(encryptionKey);
    }

    public void unlock() {
        if (!this.isEncrypted()) {
            // Nothing to do
            return;
        }

        String encryptionKey = this.getEncryptionKey();
        if (encryptionKey == null) {
            // Can't encrypt without a key
            return;
        }

        this.doUnlock(encryptionKey);
    }

    public boolean save() throws IOException {
        if (!isInitialized()) {
            LOGGER.info("Error: can't save wallet, because no wallet it initialized");
            return false;
        }

        // Encrypt first (will do nothing if already encrypted)
        this.encrypt();

        String wallet64 = LiteWalletJni.save();
        byte[] wallet;
        try {
            wallet = Base64.decode(wallet64);
        }
        catch (DecoderException e) {
            LOGGER.info("Unable to decode wallet");
            return false;
        }
        if (wallet == null) {
            LOGGER.info("Unable to save wallet");
            return false;
        }

        Path walletPath = this.getCurrentWalletPath();
        Files.createDirectories(walletPath.getParent());
        Files.write(walletPath, wallet, StandardOpenOption.CREATE);

        LOGGER.debug("Saved Pirate Chain wallet");

        return true;
    }

    public String load() throws IOException {
        Path walletPath = this.getCurrentWalletPath();
        if (!Files.exists(walletPath)) {
            return null;
        }
        byte[] wallet = Files.readAllBytes(walletPath);
        if (wallet == null) {
            return null;
        }
        String wallet64 = Base64.toBase64String(wallet);
        return wallet64;
    }

    private String getEntropyHash58() {
        if (this.entropyBytes == null) {
            return null;
        }
        byte[] entropyHash = Crypto.digest(this.entropyBytes);
        return Base58.encode(entropyHash);
    }

    public String getSeedPhrase() {
        return this.seedPhrase;
    }

    private String getEncryptionKey() {
        if (this.entropyBytes == null) {
            return null;
        }

        // Prefix the bytes with a (deterministic) string, to ensure that the resulting hash is different
        String prefix = "ARRRWalletEncryption";

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write(prefix.getBytes(StandardCharsets.UTF_8));
            outputStream.write(this.entropyBytes);

        } catch (IOException e) {
            return null;
        }

        byte[] encryptionKeyHash = Crypto.digest(outputStream.toByteArray());
        return Base58.encode(encryptionKeyHash);
    }

    private Path getCurrentWalletPath() {
        String entropyHash58 = this.getEntropyHash58();
        String filename = String.format("wallet-%s.dat", entropyHash58);
        return Paths.get(Settings.getInstance().getWalletsPath(), "PirateChain", filename);
    }

    public boolean isInitialized() {
        return this.entropyBytes != null && this.ready;
    }

    public boolean isSynchronized() {
        Integer height = this.getHeight();
        Integer chainTip = this.getChainTip();

        if (height == null || chainTip == null) {
            return false;
        }

        // Assume synchronized if within 2 blocks of the chain tip
        return height >= (chainTip - 2);
    }


    // APIs

    public Integer getHeight() {
        String response = LiteWalletJni.execute("height", "");
        JSONObject json = new JSONObject(response);
        if (json.has("height")) {
            return json.getInt("height");
        }
        return null;
    }

    public Integer getChainTip() {
        String response = LiteWalletJni.execute("info", "");
        JSONObject json = new JSONObject(response);
        if (json.has("latest_block_height")) {
            return json.getInt("latest_block_height");
        }
        return null;
    }

    public boolean isNullSeedWallet() {
        return this.isNullSeedWallet;
    }

    public Boolean isEncrypted() {
        String response = LiteWalletJni.execute("encryptionstatus", "");
        JSONObject json = new JSONObject(response);
        if (json.has("encrypted")) {
            return json.getBoolean("encrypted");
        }
        return null;
    }

    public boolean doEncrypt(String key) {
        String response = LiteWalletJni.execute("encrypt", key);
        JSONObject json = new JSONObject(response);
        String result = json.getString("result");
        if (json.has("result")) {
            return (Objects.equals(result, "success"));
        }
        return false;
    }

    public boolean doDecrypt(String key) {
        String response = LiteWalletJni.execute("decrypt", key);
        JSONObject json = new JSONObject(response);
        String result = json.getString("result");
        if (json.has("result")) {
            return (Objects.equals(result, "success"));
        }
        return false;
    }

    public boolean doUnlock(String key) {
        String response = LiteWalletJni.execute("unlock", key);
        JSONObject json = new JSONObject(response);
        String result = json.getString("result");
        if (json.has("result")) {
            return (Objects.equals(result, "success"));
        }
        return false;
    }

    public String getWalletAddress() {
        // Get balance, which also contains wallet addresses
        String response = LiteWalletJni.execute("balance", "");
        JSONObject json = new JSONObject(response);
        String address = null;

        if (json.has("z_addresses")) {
            JSONArray z_addresses = json.getJSONArray("z_addresses");

            if (z_addresses != null && !z_addresses.isEmpty()) {
                JSONObject firstAddress = z_addresses.getJSONObject(0);
                if (firstAddress.has("address")) {
                    address = firstAddress.getString("address");
                }
            }
        }
        return address;
    }

    public String getPrivateKey() {
        String response = LiteWalletJni.execute("export", "");
        JSONArray addressesJson = new JSONArray(response);
        if (!addressesJson.isEmpty()) {
            JSONObject addressJson = addressesJson.getJSONObject(0);
            if (addressJson.has("private_key")) {
                //String address = addressJson.getString("address");
                String privateKey = addressJson.getString("private_key");
                //String viewingKey = addressJson.getString("viewing_key");

                return privateKey;
            }
        }
        return null;
    }

}
