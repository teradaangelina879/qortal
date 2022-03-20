package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.*;

import java.security.SecureRandom;
import java.util.*;

import static org.qortal.network.message.Message.MessageType;
import static org.qortal.network.message.Message.MessageType.*;

public class LiteNode {

    private static final Logger LOGGER = LogManager.getLogger(LiteNode.class);

    private static LiteNode instance;


    public Map<Integer, Long> pendingRequests = Collections.synchronizedMap(new HashMap<>());


    public LiteNode() {

    }

    public static synchronized LiteNode getInstance() {
        if (instance == null) {
            instance = new LiteNode();
        }

        return instance;
    }


    /**
     * Fetch account data from peers for given QORT address
     * @param address - the QORT address to query
     * @return accountData - the account data for this address, or null if not retrieved
     */
    public AccountData fetchAccountData(String address) {
        GetAccountMessage getAccountMessage = new GetAccountMessage(address);
        AccountMessage accountMessage = (AccountMessage) this.sendMessage(getAccountMessage, ACCOUNT);
        if (accountMessage == null) {
            return null;
        }
        return accountMessage.getAccountData();
    }

    /**
     * Fetch account balance data from peers for given QORT address and asset ID
     * @param address - the QORT address to query
     * @return balance - the balance for this address and assetId, or null if not retrieved
     */
    public AccountBalanceData fetchAccountBalance(String address, long assetId) {
        GetAccountBalanceMessage getAccountMessage = new GetAccountBalanceMessage(address, assetId);
        AccountBalanceMessage accountMessage = (AccountBalanceMessage) this.sendMessage(getAccountMessage, ACCOUNT_BALANCE);
        if (accountMessage == null) {
            return null;
        }
        return accountMessage.getAccountBalanceData();
    }


    private Message sendMessage(Message message, MessageType expectedResponseMessageType) {
        // This asks a random peer for the data
        // TODO: ask multiple peers, and disregard everything if there are any significant differences in the responses

        // Needs a mutable copy of the unmodifiableList
        List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());

        // Disregard peers that have "misbehaved" recently
        peers.removeIf(Controller.hasMisbehaved);

        // Disregard peers that only have genesis block
        peers.removeIf(Controller.hasOnlyGenesisBlock);

        // Disregard peers that are on an old version
        peers.removeIf(Controller.hasOldVersion);

        // Disregard peers that are on a known inferior chain tip
        peers.removeIf(Controller.hasInferiorChainTip);

        if (peers.isEmpty()) {
            LOGGER.info("No peers available to send {} message to", message.getType());
            return null;
        }

        // Pick random peer
        int index = new SecureRandom().nextInt(peers.size());
        Peer peer = peers.get(index);

        LOGGER.info("Sending {} message to peer {}...", message.getType(), peer);

        Message responseMessage;

        try {
            responseMessage = peer.getResponse(message);

        } catch (InterruptedException e) {
            return null;
        }

        if (responseMessage == null || responseMessage.getType() != expectedResponseMessageType) {
            return null;
        }

        LOGGER.info("Peer {} responded with {} message", peer, responseMessage.getType());

        return responseMessage;
    }

}
