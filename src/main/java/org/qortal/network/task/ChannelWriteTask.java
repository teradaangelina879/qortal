package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.utils.ExecuteProduceConsume.Task;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ChannelWriteTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(ChannelWriteTask.class);

    private final SelectionKey selectionKey;
    private final SocketChannel socketChannel;
    private final Peer peer;
    private final String name;

    public ChannelWriteTask(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
        this.socketChannel = (SocketChannel) this.selectionKey.channel();
        this.peer = Network.getInstance().getPeerFromChannel(this.socketChannel);
        this.name = "ChannelWriteTask::" + peer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        if (peer == null) {
            return;
        }

        try {
            boolean isMoreDataPending = peer.writeChannel();

            if (isMoreDataPending) {
                Network.getInstance().setInterestOps(socketChannel, SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("connection reset")) {
                peer.disconnect("Connection reset");
                return;
            }

            LOGGER.trace("[{}] Network thread {} encountered I/O error: {}", peer.getPeerConnectionId(),
                    Thread.currentThread().getId(), e.getMessage(), e);
            peer.disconnect("I/O error");
        }
    }
}
