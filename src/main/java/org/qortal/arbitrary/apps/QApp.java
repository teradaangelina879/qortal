package org.qortal.arbitrary.apps;

import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.util.encoders.Base64;
import org.ciyam.at.MachineState;
import org.qortal.account.Account;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.asset.Asset;
import org.qortal.controller.Controller;
import org.qortal.controller.LiteNode;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.arbitrary.ArbitraryResourceInfo;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.group.GroupData;
import org.qortal.data.naming.NameData;
import org.qortal.list.ResourceListManager;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class QApp {

    public static AccountData getAccountData(String address) throws DataException {
        if (!Crypto.isValidAddress(address))
            throw new IllegalArgumentException("Invalid address");

        try (final Repository repository = RepositoryManager.getRepository()) {
            return repository.getAccountRepository().getAccount(address);
        }
    }

    public static List<NameData> getAccountNames(String address) throws DataException {
        if (!Crypto.isValidAddress(address))
            throw new IllegalArgumentException("Invalid address");

        try (final Repository repository = RepositoryManager.getRepository()) {
            return repository.getNameRepository().getNamesByOwner(address);
        }
    }

    public static NameData getNameData(String name) throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            if (Settings.getInstance().isLite()) {
                return LiteNode.getInstance().fetchNameData(name);
            } else {
                return repository.getNameRepository().fromName(name);
            }
        }
    }

    public static List<ChatMessage> searchChatMessages(Long before, Long after, Integer txGroupId, List<String> involvingAddresses,
                                                       String reference, String chatReference, Boolean hasChatReference,
                                                       Integer limit, Integer offset, Boolean reverse) throws DataException {
        // Check args meet expectations
        if ((txGroupId == null && involvingAddresses.size() != 2)
                || (txGroupId != null && !involvingAddresses.isEmpty()))
            throw new IllegalArgumentException("Invalid txGroupId or involvingAddresses");

        // Check any provided addresses are valid
        if (involvingAddresses.stream().anyMatch(address -> !Crypto.isValidAddress(address)))
            throw new IllegalArgumentException("Invalid address");

        if (before != null && before < 1500000000000L)
            throw new IllegalArgumentException("Invalid timestamp");

        byte[] referenceBytes = null;
        if (reference != null)
            referenceBytes = Base58.decode(reference);

        byte[] chatReferenceBytes = null;
        if (chatReference != null)
            chatReferenceBytes = Base58.decode(chatReference);

        try (final Repository repository = RepositoryManager.getRepository()) {
            return repository.getChatRepository().getMessagesMatchingCriteria(
                    before,
                    after,
                    txGroupId,
                    referenceBytes,
                    chatReferenceBytes,
                    hasChatReference,
                    involvingAddresses,
                    limit, offset, reverse);
        }
    }

    public static List<ArbitraryResourceInfo> searchQdnResources(Service service, String identifier, Boolean defaultResource,
                                                                 String nameListFilter, Boolean includeStatus, Boolean includeMetadata,
                                                                 Integer limit, Integer offset, Boolean reverse) throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Treat empty identifier as null
            if (identifier != null && identifier.isEmpty()) {
                identifier = null;
            }

            // Ensure that "default" and "identifier" parameters cannot coexist
            boolean defaultRes = Boolean.TRUE.equals(defaultResource);
            if (defaultRes == true && identifier != null) {
                throw new IllegalArgumentException("identifier cannot be specified when requesting a default resource");
            }

            // Load filter from list if needed
            List<String> names = null;
            if (nameListFilter != null) {
                names = ResourceListManager.getInstance().getStringsInList(nameListFilter);
                if (names.isEmpty()) {
                    // List doesn't exist or is empty - so there will be no matches
                    return new ArrayList<>();
                }
            }

            List<ArbitraryResourceInfo> resources = repository.getArbitraryRepository()
                    .getArbitraryResources(service, identifier, names, defaultRes, limit, offset, reverse);

            if (resources == null) {
                return new ArrayList<>();
            }

            if (includeStatus != null && includeStatus) {
                resources = ArbitraryTransactionUtils.addStatusToResources(resources);
            }
            if (includeMetadata != null && includeMetadata) {
                resources = ArbitraryTransactionUtils.addMetadataToResources(resources);
            }

            return resources;

        }
    }

    public static ArbitraryResourceStatus getQdnResourceStatus(Service service, String name, String identifier) {
        return ArbitraryTransactionUtils.getStatus(service, name, identifier, false);
    }

    public static String fetchQdnResource64(Service service, String name, String identifier, String filepath, boolean rebuild) throws DataException {
        ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
        try {

            int attempts = 0;
            int maxAttempts = 5;

            // Loop until we have data
            while (!Controller.isStopping()) {
                attempts++;
                if (!arbitraryDataReader.isBuilding()) {
                    try {
                        arbitraryDataReader.loadSynchronously(rebuild);
                        break;
                    } catch (MissingDataException e) {
                        if (attempts > maxAttempts) {
                            // Give up after 5 attempts
                            throw new DataException("Data unavailable. Please try again later.");
                        }
                    }
                }
                Thread.sleep(3000L);
            }

            java.nio.file.Path outputPath = arbitraryDataReader.getFilePath();
            if (outputPath == null) {
                // Assume the resource doesn't exist
                throw new DataException("File not found");
            }

            if (filepath == null || filepath.isEmpty()) {
                // No file path supplied - so check if this is a single file resource
                String[] files = ArrayUtils.removeElement(outputPath.toFile().list(), ".qortal");
                if (files.length == 1) {
                    // This is a single file resource
                    filepath = files[0];
                }
                else {
                    throw new IllegalArgumentException("filepath is required for resources containing more than one file");
                }
            }

            // TODO: limit file size that can be read into memory
            java.nio.file.Path path = Paths.get(outputPath.toString(), filepath);
            if (!Files.exists(path)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes != null) {
                return Base64.toBase64String(bytes);
            }
            throw new DataException("File contents could not be read");

        } catch (Exception e) {
            throw new DataException(String.format("Unable to fetch resource: %s", e.getMessage()));
        }
    }

    public static List<GroupData> listGroups(Integer limit, Integer offset, Boolean reverse) throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            List<GroupData> allGroupData = repository.getGroupRepository().getAllGroups(limit, offset, reverse);
            allGroupData.forEach(groupData -> {
                try {
                    groupData.memberCount = repository.getGroupRepository().countGroupMembers(groupData.getGroupId());
                } catch (DataException e) {
                    // Exclude memberCount for this group
                }
            });
            return allGroupData;
        }
    }

    public static Long getBalance(Long assetId, String address) throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            if (assetId == null)
                assetId = Asset.QORT;

            Account account = new Account(repository, address);
            return account.getConfirmedBalance(assetId);
        }
    }

    public static ATData getAtInfo(String atAddress) throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            ATData atData = repository.getATRepository().fromATAddress(atAddress);
            if (atData == null) {
                throw new IllegalArgumentException("AT not found");
            }
            return atData;
        }
    }

    public static String getAtData58(String atAddress) throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
            if (atStateData == null) {
                throw new IllegalArgumentException("AT not found");
            }
            byte[] stateData = atStateData.getStateData();
            byte[] dataBytes = MachineState.extractDataBytes(stateData);
            return Base58.encode(dataBytes);
        }
    }

    public static List<ATData> listATs(String codeHash58, Boolean isExecutable, Integer limit, Integer offset, Boolean reverse) throws DataException {
        // Decode codeHash
        byte[] codeHash;
        try {
            codeHash = Base58.decode(codeHash58);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(e);
        }

        // codeHash must be present and have correct length
        if (codeHash == null || codeHash.length != 32)
            throw new IllegalArgumentException("Invalid code hash");

        // Impose a limit on 'limit'
        if (limit != null && limit > 100)
            throw new IllegalArgumentException("Limit is too high");

        try (final Repository repository = RepositoryManager.getRepository()) {
            return repository.getATRepository().getATsByFunctionality(codeHash, isExecutable, limit, offset, reverse);
        }
    }
}
