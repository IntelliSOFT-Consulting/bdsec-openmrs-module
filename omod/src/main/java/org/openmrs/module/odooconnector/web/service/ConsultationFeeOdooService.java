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
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * Forwards consultation fee payloads from OpenMRS to the Odoo sales endpoint.
 *
 * Odoo connection settings are read from OpenMRS Global Properties:
 *   odooconnector.odooUrl                — Odoo base URL (no trailing slash)
 *   odooconnector.odooDb                 — Odoo database name
 *   odooconnector.odooLogin              — Odoo service-account login
 *   odooconnector.odooPassword           — Odoo service-account password
 *   odooconnector.consultation.odooPath  — Odoo sales API path
 *   odooconnector.consultation.shopId    — Odoo shop_id for consultation invoices
 *
 * The lines array is always [{default_code: "consultation", quantity: 1}] because this
 * endpoint is exclusively for consultation billing. Odoo determines the price from the
 * product configured under that code.
 */
@Service
public class ConsultationFeeOdooService {

	protected final Log log = LogFactory.getLog(getClass());

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	// ---------- Global Property accessors ----------

	private String getOdooBaseUrl() {
		String url = Context.getAdministrationService().getGlobalProperty("odooconnector.odooUrl");
		return (url != null && !url.isEmpty()) ? url : "https://bdsec.grace-erp-consultancy.com";
	}

	private String getOdooDb() {
		String db = Context.getAdministrationService().getGlobalProperty("odooconnector.odooDb");
		return (db != null && !db.isEmpty()) ? db : "odoo";
	}

	private String getOdooLogin() {
		String login = Context.getAdministrationService().getGlobalProperty("odooconnector.odooLogin");
		return (login != null && !login.isEmpty()) ? login : "emrsync";
	}

	private String getOdooPassword() {
		String password = Context.getAdministrationService().getGlobalProperty("odooconnector.odooPassword");
		return (password != null && !password.isEmpty()) ? password : "Admin123";
	}

	private String getConsultationOdooPath() {
		String path = Context.getAdministrationService().getGlobalProperty("odooconnector.consultation.odooPath");
		return (path != null && !path.isEmpty()) ? path : "/api/bdsec/sales";
	}

	private int getShopId() {
		String shopId = Context.getAdministrationService().getGlobalProperty("odooconnector.consultation.shopId");
		if (shopId != null && !shopId.isEmpty()) {
			try {
				return Integer.parseInt(shopId.trim());
			}
			catch (NumberFormatException ignored) {}
		}
		return 1;
	}

	private int getIntGlobalProperty(String property, int defaultValue) {
		String value = Context.getAdministrationService().getGlobalProperty(property);
		if (value != null && !value.trim().isEmpty()) {
			try {
				return Integer.parseInt(value.trim());
			}
			catch (NumberFormatException ignored) {}
		}
		return defaultValue;
	}

	private int getConnectTimeoutSeconds() {
		return getIntGlobalProperty("odooconnector.consultation.connectTimeoutSeconds", 15);
	}

	private int getReadTimeoutSeconds() {
		return getIntGlobalProperty("odooconnector.consultation.readTimeoutSeconds", 30);
	}

	private int getMaxRetries() {
		return getIntGlobalProperty("odooconnector.consultation.maxRetries", 2);
	}

	private long getRetryBackoffMillis() {
		return getIntGlobalProperty("odooconnector.consultation.retryBackoffMillis", 1500);
	}

	// ---------- Authentication ----------

	/**
	 * Authenticates against Odoo using JSON-RPC and returns the session_id.
	 *
	 * Odoo 18+ returns session_id as a Set-Cookie header rather than in the JSON body.
	 * This method checks the cookie header first, then falls back to the JSON body
	 * (for compatibility with Odoo 16 and earlier).
	 */
	private String authenticate(OkHttpClient client) throws IOException {
		String authUrl = getOdooBaseUrl() + "/web/session/authenticate";
		String authBody = String.format(
		        "{\"jsonrpc\":\"2.0\",\"method\":\"call\","
		                + "\"params\":{\"db\":\"%s\",\"login\":\"%s\",\"password\":\"%s\"}}",
		        getOdooDb(), getOdooLogin(), getOdooPassword());

		log.info("[ConsultationFee] Authenticating with Odoo — url=" + authUrl
		        + " db=" + getOdooDb() + " login=" + getOdooLogin());

		Request authRequest = new Request.Builder()
		        .url(authUrl)
		        .post(RequestBody.create(authBody, JSON))
		        .addHeader("Content-Type", "application/json")
		        .build();

		try (Response response = client.newCall(authRequest).execute()) {
			String responseBody = response.body() != null ? response.body().string() : "";
			log.info("[ConsultationFee] Auth response — HTTP " + response.code());

			if (!response.isSuccessful()) {
				throw new IOException("Odoo authentication failed — HTTP " + response.code()
				        + " body: " + responseBody);
			}

			// Odoo 18+: session_id is in Set-Cookie header
			String sessionId = extractSessionIdFromCookie(response);

			// Odoo ≤16 fallback: session_id is in the JSON response body
			if (sessionId == null || sessionId.isEmpty()) {
				log.info("[ConsultationFee] session_id not in cookie, trying JSON body (Odoo ≤16)");
				try {
					SimpleObject result = SimpleObject.parseJson(responseBody);
					HashMap<?, ?> resultObj = result.get("result");
					if (resultObj != null) {
						sessionId = (String) resultObj.get("session_id");
					}
				}
				catch (Exception parseEx) {
					log.warn("[ConsultationFee] Could not parse auth response JSON: " + parseEx.getMessage());
				}
			}

			if (sessionId == null || sessionId.isEmpty()) {
				throw new IOException("Odoo auth response contains no session_id "
				        + "(checked Set-Cookie header and JSON body) — HTTP " + response.code());
			}

			log.info("[ConsultationFee] Authentication succeeded — session_id obtained");
			return sessionId;
		}
	}

	/**
	 * Extracts session_id from the Set-Cookie response header.
	 * Odoo 18 sends: Set-Cookie: session_id=<value>; Path=/; ...
	 */
	private String extractSessionIdFromCookie(Response response) {
		for (String cookie : response.headers("Set-Cookie")) {
			for (String part : cookie.split(";")) {
				String trimmed = part.trim();
				if (trimmed.startsWith("session_id=")) {
					String value = trimmed.substring("session_id=".length()).trim();
					if (!value.isEmpty()) {
						return value;
					}
				}
			}
		}
		return null;
	}

	// ---------- Main forward method ----------

	/**
	 * Authenticates with Odoo, then POSTs the consultation sale payload to the configured
	 * sales endpoint. Returns a SimpleObject summarising the Odoo response.
	 * This method is non-fatal — if Odoo is unreachable the encounter has already been saved.
	 */
	public SimpleObject forwardConsultationFee(
	        String patientId, String patientUuid, String visitUuid,
	        String paymentMethod, String modeOfPayment,
	        Object voided, String dateCreated, String dateChanged, String createdBy) {

		String odooEndpoint = getOdooBaseUrl() + getConsultationOdooPath();
		int shopId = getShopId();

		log.info("[ConsultationFee] Preparing Odoo payload"
		        + " — endpoint=" + odooEndpoint
		        + " patient_unique_id=" + patientId
		        + " patientUuid=" + patientUuid
		        + " VisitUuid=" + visitUuid
		        + " Payment_method=" + paymentMethod
		        + " Payment_type=" + modeOfPayment
		        + " shop_id=" + shopId
		        + " default_code=consultation quantity=1");

		String salesBody = buildSalesPayloadJson(
		        patientId, patientUuid, visitUuid, shopId,
		        paymentMethod, modeOfPayment,
		        voided, dateCreated, dateChanged, createdBy);

		log.info("[ConsultationFee] Payload to Odoo: " + salesBody);

		OkHttpClient client = new OkHttpClient.Builder()
		        .connectTimeout(getConnectTimeoutSeconds(), TimeUnit.SECONDS)
		        .readTimeout(getReadTimeoutSeconds(), TimeUnit.SECONDS)
		        .writeTimeout(getReadTimeoutSeconds(), TimeUnit.SECONDS)
		        .build();

		int maxAttempts = getMaxRetries() + 1;
		IOException lastError = null;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				log.info("[ConsultationFee] Attempt " + attempt + "/" + maxAttempts
				        + " — authenticating with Odoo for patientUuid=" + patientUuid);
				String sessionId = authenticate(client);
				return postSalesOrder(client, sessionId, salesBody, odooEndpoint, patientId, patientUuid, visitUuid);
			}
			catch (IOException e) {
				lastError = e;
				boolean retryable = isRetryableConnectionError(e) && attempt < maxAttempts;
				log.warn("[ConsultationFee] Attempt " + attempt + " failed for patientUuid=" + patientUuid
				        + " — " + e.getMessage() + (retryable ? " — retrying after backoff" : " — giving up"));
				if (retryable) {
					sleepBeforeRetry(attempt);
					continue;
				}
				break;
			}
		}

		log.error("[ConsultationFee] Patient sync to Odoo FAILED after " + maxAttempts
		        + " attempt(s) for patientUuid=" + patientUuid + " — encounter already saved, payload not forwarded: "
		        + (lastError != null ? lastError.getMessage() : "unknown error"), lastError);

		SimpleObject result = new SimpleObject();
		result.put("status", "error");
		result.put("errorType", "patient_sync_failed");
		result.put("patientSynced", false);
		result.put("message", "Unable to reach the billing system (Odoo) to synchronize the patient record after "
		        + maxAttempts + " attempt(s): " + (lastError != null ? lastError.getMessage() : "unknown error"));
		result.put("patientUuid", patientUuid);
		result.put("VisitUuid", visitUuid);
		return result;
	}

	/**
	 * Returns true only for connection-level failures (DNS, refused connection, connect-phase
	 * timeout) that happen before any data reaches Odoo — safe to retry. Read-timeouts on a
	 * request that was already sent are NOT retried here, since Odoo may have already created
	 * the sale order and a blind retry could create a duplicate (no idempotency key exists on
	 * /api/bdsec/sales, unlike the inbound billing/orders endpoint).
	 */
	private boolean isRetryableConnectionError(IOException e) {
		if (e instanceof UnknownHostException || e instanceof ConnectException) {
			return true;
		}
		String msg = e.getMessage();
		return msg != null && msg.toLowerCase().contains("connect");
	}

	private void sleepBeforeRetry(int attempt) {
		try {
			Thread.sleep(getRetryBackoffMillis() * attempt);
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Posts the sales payload to Odoo and classifies the result:
	 *   patientSynced=true  — Odoo's response contains patient_id / patient_name / bahmni_patient_id
	 *   errorType="patient_sync_failed" — patient fields absent (Odoo never recognized/created the patient)
	 *   errorType="order_failed"        — patient synced but no sale order was created
	 */
	private SimpleObject postSalesOrder(OkHttpClient client, String sessionId, String salesBody, String odooEndpoint,
	        String patientId, String patientUuid, String visitUuid) throws IOException {

		Request salesRequest = new Request.Builder()
		        .url(odooEndpoint)
		        .post(RequestBody.create(salesBody, JSON))
		        .addHeader("Content-Type", "application/json")
		        .addHeader("Cookie", "session_id=" + sessionId)
		        .build();

		try (Response salesResponse = client.newCall(salesRequest).execute()) {
			String responseBody = salesResponse.body() != null ? salesResponse.body().string() : "";
			log.info("[ConsultationFee] Odoo sales response — HTTP " + salesResponse.code()
			        + " body: " + responseBody);

			SimpleObject result = new SimpleObject();
			result.put("odooResponseCode", salesResponse.code());
			result.put("patientUuid", patientUuid);
			result.put("VisitUuid", visitUuid);

			SimpleObject odooResult = null;
			try {
				odooResult = SimpleObject.parseJson(responseBody);
				surfaceOdooResponseFields(odooResult, result);
			}
			catch (Exception parseEx) {
				log.warn("[ConsultationFee] Could not parse Odoo response as JSON: " + parseEx.getMessage());
			}

			boolean patientSynced = odooResult != null && (odooResult.containsKey("patient_id")
			        || odooResult.containsKey("patient_name") || odooResult.containsKey("bahmni_patient_id"));
			boolean orderCreated = odooResult != null
			        && (odooResult.containsKey("sale_order_id") || odooResult.containsKey("sale_order_name"));
			// Odoo's /api/bdsec/sales does not echo patient_* fields back on a sale-order-level
			// validation rejection (e.g. invalid Payment_type) — it only returns {status, error,
			// details}. That shape is still a structured response from Odoo's own application code,
			// not a sign that patient sync failed, so it must NOT be lumped in with connectivity
			// failures below.
			boolean odooStructuredError = odooResult != null
			        && (odooResult.containsKey("error") || odooResult.containsKey("status"));

			result.put("patientSynced", patientSynced);

			if (patientSynced && orderCreated && salesResponse.isSuccessful()) {
				log.info("[ConsultationFee] Patient synced and sale order created successfully"
				        + " — sale_order_id=" + result.get("sale_order_id")
				        + " sale_order_name=" + result.get("sale_order_name")
				        + " patient_name=" + result.get("patient_name"));
			}
			else if (patientSynced) {
				result.put("status", "error");
				result.put("errorType", "order_failed");
				ensureMessage(result, odooResult, "Patient synchronized, but the consultation order could not be created.");
				log.warn("[ConsultationFee] Patient synced but ORDER creation FAILED — patientUuid=" + patientUuid
				        + " patient_name=" + result.get("patient_name") + " message=" + result.get("message"));
			}
			else if (odooStructuredError) {
				result.put("status", "error");
				result.put("errorType", "order_failed");
				ensureMessage(result, odooResult, "Odoo rejected the consultation order (HTTP " + salesResponse.code() + ").");
				log.warn("[ConsultationFee] Odoo rejected the ORDER (structured error, patient status unknown) —"
				        + " patientId=" + patientId + " patientUuid=" + patientUuid
				        + " httpCode=" + salesResponse.code() + " message=" + result.get("message"));
			}
			else {
				result.put("status", "error");
				result.put("errorType", "patient_sync_failed");
				ensureMessage(result, odooResult, "Patient record could not be synchronized with the billing system "
				        + "(Odoo HTTP " + salesResponse.code() + ").");
				log.warn("[ConsultationFee] Patient sync FAILED — patientId=" + patientId
				        + " patientUuid=" + patientUuid + " httpCode=" + salesResponse.code());
			}

			return result;
		}
	}

	// ---------- Helpers ----------

	private void surfaceOdooResponseFields(SimpleObject odooResult, SimpleObject out) {
		String[] fields = {
		        "status", "message", "error", "details", "sale_order_id", "sale_order_name",
		        "patient_id", "patient_name", "bahmni_patient_id", "patient_unique_id",
		        "shop_id", "shop_name", "payment_type", "is_cbhi_patient",
		        "is_insurance_customer", "state", "warnings"
		};
		for (String field : fields) {
			if (odooResult.containsKey(field)) {
				out.put(field, odooResult.get(field));
			}
		}
	}

	/**
	 * Sets result.message from Odoo's own error detail when available (preferring the most
	 * specific field), falling back to the given generic message only when Odoo gave us nothing
	 * usable. Never overwrites a message already present.
	 */
	private void ensureMessage(SimpleObject result, SimpleObject odooResult, String fallback) {
		if (result.containsKey("message") && result.get("message") != null
		        && !String.valueOf(result.get("message")).isEmpty()) {
			return;
		}
		String detail = null;
		if (odooResult != null) {
			Object error = odooResult.get("error");
			Object details = odooResult.get("details");
			if (error != null && details != null) {
				detail = error + " " + details;
			}
			else if (error != null) {
				detail = String.valueOf(error);
			}
			else if (details != null) {
				detail = String.valueOf(details);
			}
		}
		result.put("message", detail != null && !detail.isEmpty() ? detail : fallback);
	}

	/**
	 * Builds the exact JSON payload required by the Odoo /api/bdsec/sales endpoint.
	 * Field names and casing must match Odoo's expected schema exactly.
	 *   patient_unique_id — Bahmni patient identifier (e.g. ABC200023)
	 *   patientUuid       — OpenMRS patient UUID
	 *   shop_id           — Odoo POS shop; configurable via GP
	 *   Payment_method    — payment method string (e.g. "free", "cash")
	 *   Payment_type      — payment type string (e.g. "CBHI", "OOP")
	 *   VisitUuid         — OpenMRS visit UUID
	 *   lines             — always [{default_code: "consultation", quantity: 1}]
	 */
	private String buildSalesPayloadJson(
	        String patientId, String patientUuid, String visitUuid, int shopId,
	        String paymentMethod, String modeOfPayment,
	        Object voided, String dateCreated, String dateChanged, String createdBy) {

		String safePatientId     = patientId != null ? escapeJson(patientId) : "";
		String safePatientUuid   = patientUuid != null ? escapeJson(patientUuid) : "";
		String safeVisitUuid     = visitUuid != null ? escapeJson(visitUuid) : "";
		String safePaymentMethod = paymentMethod != null ? escapeJson(paymentMethod) : "free";
		String safeModeOfPayment = modeOfPayment != null ? escapeJson(modeOfPayment) : "";
		String safeDateCreated   = dateCreated != null ? escapeJson(dateCreated) : "";
		String safeDateChanged   = dateChanged != null ? escapeJson(dateChanged) : "";
		String safeCreatedBy     = createdBy != null ? escapeJson(createdBy) : "";
		String voidedVal         = (voided instanceof Boolean) ? voided.toString() : "false";

		return "{"
		        + "\"patient_unique_id\":\"" + safePatientId + "\","
		        + "\"patientUuid\":\"" + safePatientUuid + "\","
		        + "\"shop_id\":" + shopId + ","
		        + "\"Payment_method\":\"" + safePaymentMethod + "\","
		        + "\"Payment_type\":\"" + safeModeOfPayment + "\","
		        + "\"VisitUuid\":\"" + safeVisitUuid + "\","
		        + "\"voided\":" + voidedVal + ","
		        + "\"dateCreated\":\"" + safeDateCreated + "\","
		        + "\"dateChanged\":\"" + safeDateChanged + "\","
		        + "\"createdBy\":\"" + safeCreatedBy + "\","
		        + "\"lines\":[{\"default_code\":\"consultation\",\"quantity\":1}]"
		        + "}";
	}

	/** Escapes backslashes and double-quotes so the value is safe inside a JSON string. */
	private String escapeJson(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
