package org.openmrs.module.odooconnector.web.service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;

@Service
public class BedOrderOdooService {

	protected final Log log = LogFactory.getLog(getClass());

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private String getOdooBaseUrl() {
		String url = Context.getAdministrationService().getGlobalProperty("odooconnector.odooUrl");
		return (url != null && !url.isEmpty()) ? url : "http://156.67.24.33:8069";
	}

	private String getOdooDb() {
		String db = Context.getAdministrationService().getGlobalProperty("odooconnector.odooDb");
		return (db != null && !db.isEmpty()) ? db : "odoo";
	}

	private String getOdooLogin() {
		String login = Context.getAdministrationService().getGlobalProperty("odooconnector.odooLogin");
		return (login != null && !login.isEmpty()) ? login : "admin";
	}

	private String getOdooPassword() {
		String password = Context.getAdministrationService().getGlobalProperty("odooconnector.odooPassword");
		return (password != null && !password.isEmpty()) ? password : "admin";
	}

	private String authenticate(OkHttpClient client) throws IOException {
		String authBody = String.format(
		        "{\"jsonrpc\":\"2.0\",\"method\":\"call\",\"params\":{\"db\":\"%s\",\"login\":\"%s\",\"password\":\"%s\"},\"id\":1}",
		        getOdooDb(), getOdooLogin(), getOdooPassword());

		Request authRequest = new Request.Builder()
		        .url(getOdooBaseUrl() + "/web/session/authenticate")
		        .post(RequestBody.create(authBody, JSON))
		        .build();

		try (Response response = client.newCall(authRequest).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("[BedOrder] Odoo authentication failed: " + response.code());
			}
			SimpleObject result = SimpleObject.parseJson(response.body().string());
			HashMap<?, ?> resultObj = result.get("result");
			return (String) resultObj.get("session_id");
		}
	}

	/**
	 * Forwards a bed invoice request to Odoo.
	 * Called by BedOrderController after the frontend sends POST /odooconnector/bed-order.
	 */
	public SimpleObject createBedInvoice(String patientUuid, String visitUuid, String bedId,
	        String bedNumber, String wardName,
	        Object numberOfDays, Object amountPerNight, Object totalAmount,
	        String currency, String modeOfPayment) {

		log.info("[BedOrder] Received bed order request from Bahmni frontend"
		        + " — patientUuid=" + patientUuid
		        + " visitUuid=" + visitUuid
		        + " bedId=" + bedId
		        + " bedNumber=" + bedNumber
		        + " wardName=" + wardName
		        + " numberOfDays=" + numberOfDays
		        + " amountPerNight=" + amountPerNight
		        + " totalAmount=" + totalAmount
		        + " currency=" + currency
		        + " modeOfPayment=" + modeOfPayment);

		String odooEndpoint = getOdooBaseUrl() + "/api/bed/invoice";
		log.info("[BedOrder] Forwarding bed order to Odoo — endpoint=" + odooEndpoint);

		String invoiceBody = String.format(
		        "{\"patientUuid\":\"%s\",\"visitUuid\":\"%s\",\"bedId\":\"%s\","
		                + "\"bedNumber\":\"%s\",\"wardName\":\"%s\","
		                + "\"numberOfDays\":%s,\"amountPerNight\":%s,\"totalAmount\":%s,"
		                + "\"currency\":\"%s\",\"modeOfPayment\":\"%s\"}",
		        patientUuid,
		        visitUuid,
		        bedId,
		        bedNumber,
		        wardName,
		        (numberOfDays  != null ? numberOfDays  : "null"),
		        (amountPerNight != null ? amountPerNight : "null"),
		        (totalAmount   != null ? totalAmount   : "null"),
		        (currency      != null ? currency      : "KES"),
		        (modeOfPayment != null ? modeOfPayment : ""));

		OkHttpClient client = new OkHttpClient();
		try {
			String sessionId = authenticate(client);

			Request invoiceRequest = new Request.Builder()
			        .url(odooEndpoint)
			        .post(RequestBody.create(invoiceBody, JSON))
			        .addHeader("Cookie", "session_id=" + sessionId)
			        .build();

			try (Response invoiceResponse = client.newCall(invoiceRequest).execute()) {
				String responseBody = invoiceResponse.body().string();
				log.info("[BedOrder] Odoo response received (HTTP " + invoiceResponse.code() + "): "
				        + responseBody);

				SimpleObject result = new SimpleObject();
				result.put("status", "order_created");
				result.put("odooResponseCode", invoiceResponse.code());
				result.put("patientUuid", patientUuid);
				result.put("visitUuid", visitUuid);
				result.put("bedId", bedId);

				// Surface invoiceId if Odoo returns it
				try {
					SimpleObject odooResult = SimpleObject.parseJson(responseBody);
					if (odooResult.containsKey("invoiceId")) {
						result.put("invoiceId", odooResult.get("invoiceId"));
					}
					if (odooResult.containsKey("totalAmount")) {
						result.put("totalAmount", odooResult.get("totalAmount"));
					}
				}
				catch (Exception ignored) { /* Odoo response may not be JSON in all cases */ }

				return result;
			}
		}
		catch (IOException e) {
			log.error("[BedOrder] Failed to reach Odoo — " + e.getMessage(), e);

			SimpleObject result = new SimpleObject();
			result.put("status", "order_logged");
			result.put("error", e.getMessage());
			result.put("patientUuid", patientUuid);
			result.put("visitUuid", visitUuid);
			result.put("bedId", bedId);
			return result;
		}
	}

	/**
	 * Queries Odoo for the current bed payment status for a patient.
	 * Called by BedOrderController on GET /odooconnector/bed-order/payment-status.
	 */
	public SimpleObject checkPaymentStatus(String patientUuid, String bedId) {
		log.info("[BedOrder] Payment status check request received"
		        + " — patientUuid=" + patientUuid + " bedId=" + bedId);

		String odooEndpoint = getOdooBaseUrl()
		        + "/api/bed/payment-status?patientUuid=" + patientUuid + "&bedId=" + bedId;
		log.info("[BedOrder] Forwarding payment status check to Odoo — endpoint=" + odooEndpoint);

		OkHttpClient client = new OkHttpClient();
		try {
			String sessionId = authenticate(client);

			Request statusRequest = new Request.Builder()
			        .url(odooEndpoint)
			        .get()
			        .addHeader("Cookie", "session_id=" + sessionId)
			        .build();

			try (Response statusResponse = client.newCall(statusRequest).execute()) {
				String responseBody = statusResponse.body().string();
				log.info("[BedOrder] Odoo response received (HTTP " + statusResponse.code() + "): "
				        + responseBody);

				if (statusResponse.isSuccessful()) {
					SimpleObject result = SimpleObject.parseJson(responseBody);
					log.info("[BedOrder] Payment status response from Odoo"
					        + " — paid=" + result.get("paid")
					        + " invoiceId=" + result.get("invoiceId"));
					return result;
				}

				log.warn("[BedOrder] Odoo returned non-success HTTP " + statusResponse.code()
				        + " for payment status — treating as unpaid");
				SimpleObject unpaid = new SimpleObject();
				unpaid.put("paid", false);
				unpaid.put("message", "Payment status check failed (Odoo HTTP " + statusResponse.code() + ")");
				return unpaid;
			}
		}
		catch (IOException e) {
			log.error("[BedOrder] Failed to reach Odoo — " + e.getMessage(), e);
			throw new RuntimeException("Billing system unavailable: " + e.getMessage(), e);
		}
	}
}
