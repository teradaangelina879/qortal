package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.Block;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.transform.TransformationException;
import org.qortal.transform.block.BlockTransformer;
import org.qortal.utils.Triple;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class BlocksMessage extends Message {

	private static final Logger LOGGER = LogManager.getLogger(BlocksMessage.class);

	private List<Block> blocks;

	public BlocksMessage(List<Block> blocks) {
		this(-1, blocks);
	}

	private BlocksMessage(int id, List<Block> blocks) {
		super(id, MessageType.BLOCKS);

		this.blocks = blocks;
	}

	public List<Block> getBlocks() {
		return this.blocks;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {

		int count = bytes.getInt();
		List<Block> blocks = new ArrayList<>();

		for (int i = 0; i < count; ++i) {
			int height = bytes.getInt();

			try {
				boolean finalBlockInBuffer = (i == count-1);

				Triple<BlockData, List<TransactionData>, List<ATStateData>> blockInfo = null;
				blockInfo = BlockTransformer.fromByteBuffer(bytes, finalBlockInBuffer);
				BlockData blockData = blockInfo.getA();
				blockData.setHeight(height);

				// We are unable to obtain a valid Repository instance here, so set it to null and we will attach it later
				Block block = new Block(null, blockData, blockInfo.getB(), blockInfo.getC());
				blocks.add(block);

			} catch (TransformationException e) {
				return null;
			}

		}

		return new BlocksMessage(id, blocks);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(this.blocks.size()));

			for (Block block : this.blocks) {
				bytes.write(Ints.toByteArray(block.getBlockData().getHeight()));
				bytes.write(BlockTransformer.toBytes(block));
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		} catch (TransformationException e) {
			return null;
		}
	}

}
