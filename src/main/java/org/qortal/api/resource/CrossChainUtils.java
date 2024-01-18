package org.qortal.api.resource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Coin;
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

    /**
     * Set Fee Per Kb
     *
     * @param bitcoiny the blockchain support
     * @param fee the fee in satoshis
     *
     * @return the fee if valid
     *
     * @throws IllegalArgumentException if invalid
     */
    public static String setFeePerKb(Bitcoiny bitcoiny, String fee) throws IllegalArgumentException {

        long satoshis = Long.parseLong(fee);
        if( satoshis < 0 ) throw new IllegalArgumentException("can't set fee to negative number");

        bitcoiny.setFeePerKb(Coin.valueOf(satoshis) );

        return String.valueOf(bitcoiny.getFeePerKb().value);
    }

    /**
     * Set Fee Ceiling
     *
     * @param bitcoiny the blockchain support
     * @param fee the fee in satoshis
     *
     * @return the fee if valid
     *
     * @throws IllegalArgumentException if invalid
     */
    public static String setFeeCeiling(Bitcoiny bitcoiny, String fee)  throws IllegalArgumentException{

        long satoshis = Long.parseLong(fee);
        if( satoshis < 0 ) throw new IllegalArgumentException("can't set fee to negative number");

        bitcoiny.setFeeCeiling( Long.parseLong(fee));

        return String.valueOf(bitcoiny.getFeeCeiling());
    }
}