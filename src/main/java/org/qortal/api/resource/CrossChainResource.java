package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;

import org.ciyam.at.MachineState;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.asset.Asset;
import org.qortal.at.QortalAtLoggerFactory;
import org.qortal.crosschain.BTCACCT;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

@Path("/crosschain")
@Tag(name = "Cross-Chain")
public class CrossChainResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/tradeoffers")
	@Operation(
		summary = "Find cross-chain trade offers",
		responses = {
			@ApiResponse(
				description = "automated transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = CrossChainTradeData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE
	})
	public List<CrossChainTradeData> getTradeOffers(
			@Parameter( ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter( ref = "offset" ) @QueryParam("offset") Integer offset,
			@Parameter( ref = "reverse" ) @QueryParam("reverse") Boolean reverse) {
		// Impose a limit on 'limit'
		if (limit != null && limit > 100)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] codeHash = BTCACCT.CODE_BYTES_HASH;
		boolean isExecutable = true;

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<ATData> atsData = repository.getATRepository().getATsByFunctionality(codeHash, isExecutable, limit, offset, reverse);

			List<CrossChainTradeData> crossChainTradesData = new ArrayList<>();
			for (ATData atData : atsData) {
				String atAddress = atData.getATAddress();

				ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

				QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();
				byte[] dataBytes = MachineState.extractDataBytes(loggerFactory, atStateData.getStateData());

				CrossChainTradeData crossChainTradeData = new CrossChainTradeData();
				crossChainTradeData.qortalAddress = atAddress;
				crossChainTradeData.qortalCreator = Crypto.toAddress(atData.getCreatorPublicKey());
				crossChainTradeData.creationTimestamp = atData.getCreation();
				crossChainTradeData.qortBalance = repository.getAccountRepository().getBalance(atAddress, Asset.QORT).getBalance();

				BTCACCT.populateTradeData(crossChainTradeData, dataBytes);

				crossChainTradesData.add(crossChainTradeData);
			}

			return crossChainTradesData;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}