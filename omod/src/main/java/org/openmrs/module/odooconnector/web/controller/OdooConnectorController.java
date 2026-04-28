/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 * <p>
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.odooconnector.web.controller;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.util.HashMap;

/**
 * This class configured as controller using annotation and mapped with the URL of
 * 'module/${rootArtifactid}/${rootArtifactid}Link.form'.
 */
@Controller("${rootrootArtifactid}.OdooconnectorController")
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/" + "odooconnector")
public class OdooConnectorController {
	
	/** Logger for this class and subclasses */
	protected final Log log = LogFactory.getLog(getClass());
	
	public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	
	@RequestMapping(value = "/patient-balance", method = RequestMethod.GET)
	public @ResponseBody SimpleObject onGet(@RequestParam String identifier) throws IOException {
		String authUrl = "http://156.67.24.33:8069/web/session/authenticate";
		String walletUrl = "http://156.67.24.33:8069/api/wallet/info";
		String authBody = "{\"jsonrpc\":\"2.0\",\"method\":\"call\",\"params\":{\"db\":\"odoo\",\"login\":\"admin\",\"password\":\"admin\"},\"id\":123}";

		OkHttpClient client = new OkHttpClient();

		// Step 1: authenticate and extract session_id from response
		Request authRequest = new Request.Builder()
		        .url(authUrl)
		        .post(RequestBody.create(authBody, JSON))
		        .build();

		String sessionId;
		try (Response authResponse = client.newCall(authRequest).execute()) {
			if (!authResponse.isSuccessful()) {
				throw new IOException("Authentication failed: " + authResponse.code());
			}
			SimpleObject authResult = SimpleObject.parseJson(authResponse.body().string());
			HashMap resultObject = authResult.get("result");
			sessionId = (String) resultObject.get("session_id");
		}

		// Step 2: fetch wallet info using session_id cookie
		String walletInfoUrl = HttpUrl.parse(walletUrl).newBuilder()
		        .addQueryParameter("patient", identifier)
		        .build().toString();

		Request walletRequest = new Request.Builder()
		        .url(walletInfoUrl)
		        .get()
		        .addHeader("Cookie", "session_id=" + sessionId)
		        .build();

		try (Response walletResponse = client.newCall(walletRequest).execute()) {
			if (!walletResponse.isSuccessful()) {
				throw new IOException("Wallet info request failed: " + walletResponse.code());
			}
			return SimpleObject.parseJson(walletResponse.body().string());
		}
	}

}
