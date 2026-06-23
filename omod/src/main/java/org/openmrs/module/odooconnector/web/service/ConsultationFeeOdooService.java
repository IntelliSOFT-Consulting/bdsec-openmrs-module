package org.openmrs.module.odooconnector.web.service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;
import org.openmrs.module.odooconnector.api.OdooBillingPaymentStatusService;
import org.openmrs.module.odooconnector.web.PatientVisitResolver;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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

	@Autowired
	private BillingOrderService billingOrderService;

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

		SimpleObject alreadyCharged = blockIfAlreadyChargedForVisit(patientId, patientUuid, visitUuid);
		if (alreadyCharged != null) {
			return alreadyCharged;
		}

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
	 * Consultation is charged once per visit, not once per day — a visit that stays open across
	 * multiple days (e.g. an admitted patient) must not accumulate a new charge each time this
	 * endpoint is called for it. Returns a blocked response (and skips Odoo entirely) when a
	 * non-voided CONSULTATION record already exists for this patient/visit, regardless of its
	 * payment_status (PENDING already counts as "charged" — only a CANCELLED/voided record, which
	 * consultation fees never produce today, would not). Returns null when it's safe to proceed.
	 *
	 * Same accepted trade-off as the bed-reservation check-then-insert (see BedOrderOdooService):
	 * this table is append-only with no DB-level unique constraint, so there is a narrow,
	 * human-paced race window between this check and the row eventually being inserted.
	 */
	private SimpleObject blockIfAlreadyChargedForVisit(String patientId, String patientUuid, String visitUuid) {
		String resolvedPatientId = PatientVisitResolver.resolvePatientIdentifier(patientId, patientUuid);
		Integer resolvedVisitId = PatientVisitResolver.resolveVisitId(null, visitUuid);

		if (resolvedPatientId == null || resolvedVisitId == null) {
			log.warn("[ConsultationFee] Could not resolve patient/visit for duplicate-charge check"
			        + " — patientId=" + patientId + " patientUuid=" + patientUuid + " visitUuid=" + visitUuid
			        + " — proceeding without the check");
			return null;
		}

		OdooBillingPaymentStatus existing = Context.getService(OdooBillingPaymentStatusService.class)
		        .getPaymentStatus(resolvedPatientId, resolvedVisitId, "CONSULTATION");

		if (existing == null) {
			return null;
		}

		log.info("[ConsultationFee] Blocked duplicate charge — a CONSULTATION record already exists for"
		        + " patientId=" + resolvedPatientId + " visitId=" + resolvedVisitId
		        + " (existing id=" + existing.getId() + " status=" + existing.getPaymentStatus() + ")");

		SimpleObject blocked = new SimpleObject();
		blocked.put("status", "blocked");
		blocked.put("errorType", "consultation_already_charged");
		blocked.put("message", "Consultation fee for the current visit has already been paid.");
		blocked.put("patientUuid", patientUuid);
		blocked.put("VisitUuid", visitUuid);
		blocked.put("existingPaymentStatus", existing.getPaymentStatus());
		return blocked;
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
				replicatePendingPaymentStatus(odooResult, patientId, patientUuid, visitUuid);
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

	/**
	 * Replicates the successful Odoo sales response into odoo_billing_payment_status via
	 * BillingOrderService — the same persistence path Odoo's own billing/orders push uses (Part B
	 * of the integration) — so a row exists with payment_status=PENDING as soon as the
	 * consultation sale order is created, rather than waiting for Odoo to push the real payment
	 * confirmation later. Best-effort: any failure here is logged and swallowed so it can never
	 * affect the consultation-fee response already being returned to the Bahmni frontend.
	 */
	private void replicatePendingPaymentStatus(SimpleObject odooResult, String patientId, String patientUuid,
	        String visitUuid) {
		try {
			Map<String, Object> billingPayload = new HashMap<>();
			billingPayload.put("sale_id", odooResult.get("sale_order_id"));
			billingPayload.put("sale_name", odooResult.get("sale_order_name"));
			billingPayload.put("patient_id", patientId);
			billingPayload.put("patient_uuid", patientUuid);
			billingPayload.put("visit_uuid", visitUuid);
			billingPayload.put("payment_status", "Pending");
			Object paymentType = odooResult.get("payment_type");
			if (paymentType != null) {
				billingPayload.put("customer_type", paymentType);
			}
			billingPayload.put("services",
			        Collections.singletonList(Collections.singletonMap("serviceType", "Consultation")));

			SimpleObject billingResult = billingOrderService.processBillingOrder(billingPayload);
			log.info("[ConsultationFee] Replicated Odoo sales response to odoo_billing_payment_status as PENDING"
			        + " — sale_order_id=" + odooResult.get("sale_order_id") + " result=" + billingResult);
		}
		catch (Exception e) {
			log.warn("[ConsultationFee] Failed to replicate Odoo sales response to odoo_billing_payment_status"
			        + " — patientUuid=" + patientUuid + " VisitUuid=" + visitUuid + ": " + e.getMessage(), e);
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
	 * default_code is always "consultation" and quantity always 1 — this method is exclusively
	 * for consultation billing. See {@link OdooSalesPayloadBuilder} for the shared shape used by
	 * every other billable component (e.g. bed nights).
	 */
	private String buildSalesPayloadJson(
	        String patientId, String patientUuid, String visitUuid, int shopId,
	        String paymentMethod, String modeOfPayment,
	        Object voided, String dateCreated, String dateChanged, String createdBy) {

		return OdooSalesPayloadBuilder.buildSalesPayloadJson(
		        patientId, patientUuid, visitUuid, shopId,
		        paymentMethod, modeOfPayment,
		        voided, dateCreated, dateChanged, createdBy,
		        "consultation", 1);
	}
}
