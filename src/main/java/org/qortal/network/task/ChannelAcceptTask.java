package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.settings.Settings;
import org.qortal.utils.ExecuteProduceConsume.Task;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;

public class ChannelAcceptTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(ChannelAcceptTask.class);

    private final ServerSocketChannel serverSocketChannel;

    public ChannelAcceptTask(ServerSocketChannel serverSocketChannel) {
        this.serverSocketChannel = serverSocketChannel;
    }

    @Override
    public String getName() {
        return "ChannelAcceptTask";
    }

    @Override
    public void perform() throws InterruptedException {
        Network network = Network.getInstance();
        SocketChannel socketChannel;

        try {
            if (network.getImmutableConnectedPeers().size() >= network.getMaxPeers()) {
                // We have enough peers
                LOGGER.debug("Ignoring pending incoming connections because the server is full");
                return;
            }

            socketChannel = serverSocketChannel.accept();

            network.setInterestOps(serverSocketChannel, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            return;
        }

        // No connection actually accepted?
        if (socketChannel == null) {
            return;
        }

        PeerAddress address = PeerAddress.fromSocket(socketChannel.socket());
        List<String> fixedNetwork = Settings.getInstance().getFixedNetwork();
        if (fixedNetwork != null && !fixedNetwork.isEmpty() && network.ipNotInFixedList(address, fixedNetwork)) {
            try {
                LOGGER.debug("Connection discarded from peer {} as not in the fixed network list", address);
                socketChannel.close();
            } catch (IOException e) {
                // IGNORE
            }
            return;
        }

        final Long now = NTP.getTime();
        Peer newPeer;

        try {
            if (now == null) {
                LOGGER.debug("Connection discarded from peer {} due to lack of NTP sync", address);
                socketChannel.close();
                return;
            }

            LOGGER.debug("Connection accepted from peer {}", address);

            newPeer = new Peer(socketChannel);
            network.addConnectedPeer(newPeer);

        } catch (IOException e) {
            if (socketChannel.isOpen()) {
                try {
                    LOGGER.debug("Connection failed from peer {} while connecting/closing", address);
                    socketChannel.close();
                } catch (IOException ce) {
                    // Couldn't close?
                }
            }
            return;
        }

        network.onPeerReady(newPeer);
    }
}
