package org.qortal.api.resource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crosschain.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CrossChainUtils {
    private static final Logger LOGGER = LogManager.getLogger(CrossChainUtils.class);

    public static ServerConfigurationInfo buildServerConfigurationInfo(Bitcoiny blockchain) {

        BitcoinyBlockchainProvider blockchainProvider = blockchain.getBlockchainProvider();
        ChainableServer currentServer = blockchainProvider.getCurrentServer();

        return new ServerConfigurationInfo(
                buildInfos(blockchainProvider.getServers(), currentServer),
                buildInfos(blockchainProvider.getRemainingServers(), currentServer),
                buildInfos(blockchainProvider.getUselessServers(), currentServer)
            );
    }

    public static ServerInfo buildInfo(ChainableServer server, boolean isCurrent) {
        return new ServerInfo(
                server.averageResponseTime(),
                server.getHostName(),
                server.getPort(),
                server.getConnectionType().toString(),
                isCurrent);

    }

    public static List<ServerInfo> buildInfos(Collection<ChainableServer> servers, ChainableServer currentServer) {

        List<ServerInfo> infos = new ArrayList<>( servers.size() );

        for( ChainableServer server : servers )
        {
            infos.add(buildInfo(server, server.equals(currentServer)));
        }

        return infos;
    }
}
