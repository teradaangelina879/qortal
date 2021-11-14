package org.qortal.api;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

public abstract class Security {

	public static final String API_KEY_HEADER = "X-API-KEY";

	public static void checkApiCallAllowed(HttpServletRequest request) {
		ApiKey apiKey = Security.getApiKey(request);

		if (!apiKey.generated()) {
			// Not generated an API key yet, so disallow sensitive API calls
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "API key not generated");
		}

		String passedApiKey = request.getHeader(API_KEY_HEADER);
		if (passedApiKey == null) {
			// We require an API key to be passed
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "Missing 'X-API-KEY' header");
		}

		if (!apiKey.equals(passedApiKey)) {
			// The API keys must match
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "API key invalid");
		}
	}

	public static ApiKey getApiKey(HttpServletRequest request) {
		ApiKey apiKey = ApiService.getInstance().getApiKey();
		if (apiKey == null) {
			try {
				apiKey = new ApiKey();
			} catch (IOException e) {
				// Couldn't load API key - so we need to treat it as not generated, and therefore unauthorized
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.UNAUTHORIZED);
			}
			ApiService.getInstance().setApiKey(apiKey);
		}
		return apiKey;
	}

}
