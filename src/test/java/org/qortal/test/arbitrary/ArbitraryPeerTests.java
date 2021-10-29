package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.crypto.Crypto;
import org.qortal.data.network.ArbitraryPeerData;
import org.qortal.data.network.PeerData;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.utils.NTP;

import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryPeerTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testSaveArbitraryPeerData() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            String peerAddress = "127.0.0.1:12392";

            // Create random bytes to represent a signature
            byte[] signature = new byte[64];
            new Random().nextBytes(signature);

            // Make sure we don't have an entry for this hash/peer combination
            assertNull(repository.getArbitraryRepository().getArbitraryPeerDataForSignatureAndPeer(signature, peerAddress));

            // Now add this mapping to the db
            Peer peer = new Peer(new PeerData(PeerAddress.fromString(peerAddress)));
            ArbitraryPeerData arbitraryPeerData = new ArbitraryPeerData(signature, peer);
            repository.getArbitraryRepository().save(arbitraryPeerData);

            // We should now have an entry for this hash/peer combination
            ArbitraryPeerData retrievedArbitraryPeerData = repository.getArbitraryRepository()
                    .getArbitraryPeerDataForSignatureAndPeer(signature, peerAddress);
            assertNotNull(arbitraryPeerData);

            // .. and its data should match what was saved
            assertArrayEquals(Crypto.digest(signature), retrievedArbitraryPeerData.getHash());
            assertEquals(peerAddress, retrievedArbitraryPeerData.getPeerAddress());

        }
    }

    @Test
    public void testUpdateArbitraryPeerData() throws DataException, InterruptedException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            String peerAddress = "127.0.0.1:12392";

            // Create random bytes to represent a signature
            byte[] signature = new byte[64];
            new Random().nextBytes(signature);

            // Make sure we don't have an entry for this hash/peer combination
            assertNull(repository.getArbitraryRepository().getArbitraryPeerDataForSignatureAndPeer(signature, peerAddress));

            // Now add this mapping to the db
            Peer peer = new Peer(new PeerData(PeerAddress.fromString(peerAddress)));
            ArbitraryPeerData arbitraryPeerData = new ArbitraryPeerData(signature, peer);
            repository.getArbitraryRepository().save(arbitraryPeerData);

            // We should now have an entry for this hash/peer combination
            ArbitraryPeerData retrievedArbitraryPeerData = repository.getArbitraryRepository()
                    .getArbitraryPeerDataForSignatureAndPeer(signature, peerAddress);
            assertNotNull(arbitraryPeerData);

            // .. and its data should match what was saved
            assertArrayEquals(Crypto.digest(signature), retrievedArbitraryPeerData.getHash());
            assertEquals(peerAddress, retrievedArbitraryPeerData.getPeerAddress());

            // All stats should be zero
            assertEquals(Integer.valueOf(0), retrievedArbitraryPeerData.getSuccesses());
            assertEquals(Integer.valueOf(0), retrievedArbitraryPeerData.getFailures());
            assertEquals(Long.valueOf(0), retrievedArbitraryPeerData.getLastAttempted());
            assertEquals(Long.valueOf(0), retrievedArbitraryPeerData.getLastRetrieved());

            // Now modify some values and re-save
            retrievedArbitraryPeerData.incrementSuccesses(); retrievedArbitraryPeerData.incrementSuccesses(); // Twice
            retrievedArbitraryPeerData.incrementFailures(); // Once
            retrievedArbitraryPeerData.markAsAttempted();
            Thread.sleep(100);
            retrievedArbitraryPeerData.markAsRetrieved();
            repository.getArbitraryRepository().save(retrievedArbitraryPeerData);

            // Retrieve data once again
            ArbitraryPeerData updatedArbitraryPeerData = repository.getArbitraryRepository()
                    .getArbitraryPeerDataForSignatureAndPeer(signature, peerAddress);
            assertNotNull(updatedArbitraryPeerData);

            // Check the values
            assertArrayEquals(Crypto.digest(signature), updatedArbitraryPeerData.getHash());
            assertEquals(peerAddress, updatedArbitraryPeerData.getPeerAddress());
            assertEquals(Integer.valueOf(2), updatedArbitraryPeerData.getSuccesses());
            assertEquals(Integer.valueOf(1), updatedArbitraryPeerData.getFailures());
            assertTrue(updatedArbitraryPeerData.getLastRetrieved().longValue() > 0L);
            assertTrue(updatedArbitraryPeerData.getLastAttempted().longValue() > 0L);
            assertTrue(updatedArbitraryPeerData.getLastRetrieved() > updatedArbitraryPeerData.getLastAttempted());
            assertTrue(NTP.getTime() - updatedArbitraryPeerData.getLastRetrieved() < 1000);
            assertTrue(NTP.getTime() - updatedArbitraryPeerData.getLastAttempted() < 1000);
        }
    }
}
