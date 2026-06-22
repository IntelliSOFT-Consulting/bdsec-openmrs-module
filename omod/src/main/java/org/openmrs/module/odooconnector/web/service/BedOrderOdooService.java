package org.openmrs.module.odooconnector.web.service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatusDTO;
import org.openmrs.module.odooconnector.api.OdooBillingPaymentStatusService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Forwards bed-reservation (quotation) payloads from OpenMRS to the same Odoo sales endpoint
 * used for consultation fees — every billable component is treated as an Odoo sale order, the
 * only difference being lines[0].default_code (the bed number, e.g. "IPD-0001") and
 * lines[0].quantity (the number of nights). See {@link OdooSalesPayloadBuilder}.
 *
 * Odoo connection settings are read from the same Global Properties ConsultationFeeOdooService
 * uses (odooconnector.odooUrl, odooconnector.odooDb, odooconnector.odooLogin,
 * odooconnector.odooPassword, odooconnector.consultation.odooPath, odooconnector.consultation.shopId,
 * and the connect/read timeout + retry properties) — it is the same Odoo server and endpoint,
 * just a different product line, so there is no separate set of bed-specific GPs.
 *
 * Bed-reservation "locking" reuses odoo_billing_payment_status (serviceType="BED", with bed_id/
 * bed_number columns) rather than a dedicated table — see
 * {@link OdooBillingPaymentStatusService#getLatestByBedId}.
 */
@Service
public class BedOrderOdooService {

	protected final Log log = LogFactory.getLog(getClass());

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** Reservation states that mean "still holding the bed" — anything else (CANCELLED) releases it. */
	private static final List<String> ACTIVE_RESERVATION_STATUSES = Arrays.asList("PENDING", "PAID", "WAIVED");

	@Autowired
	private BillingOrderService billingOrderService;

	// ---------- Global Property accessors (shared with ConsultationFeeOdooService) ----------

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

	private String getSalesOdooPath() {
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

	// ---------- Authentication (same JSON-RPC flow as ConsultationFeeOdooService) ----------

	private String authenticate(OkHttpClient client) throws IOException {
		String authUrl = getOdooBaseUrl() + "/web/session/authenticate";
		String authBody = String.format(
		        "{\"jsonrpc\":\"2.0\",\"method\":\"call\","
		                + "\"params\":{\"db\":\"%s\",\"login\":\"%s\",\"password\":\"%s\"}}",
		        getOdooDb(), getOdooLogin(), getOdooPassword());

		log.info("[BedOrder] Authenticating with Odoo — url=" + authUrl
		        + " db=" + getOdooDb() + " login=" + getOdooLogin());

		Request authRequest = new Request.Builder()
		        .url(authUrl)
		        .post(RequestBody.create(authBody, JSON))
		        .addHeader("Content-Type", "application/json")
		        .build();

		try (Response response = client.newCall(authRequest).execute()) {
			String responseBody = response.body() != null ? response.body().string() : "";
			log.info("[BedOrder] Auth response — HTTP " + response.code());

			if (!response.isSuccessful()) {
				throw new IOException("Odoo authentication failed — HTTP " + response.code()
				        + " body: " + responseBody);
			}

			String sessionId = extractSessionIdFromCookie(response);

			if (sessionId == null || sessionId.isEmpty()) {
				log.info("[BedOrder] session_id not in cookie, trying JSON body (Odoo <=16)");
				try {
					SimpleObject result = SimpleObject.parseJson(responseBody);
					HashMap<?, ?> resultObj = result.get("result");
					if (resultObj != null) {
						sessionId = (String) resultObj.get("session_id");
					}
				}
				catch (Exception parseEx) {
					log.warn("[BedOrder] Could not parse auth response JSON: " + parseEx.getMessage());
				}
			}

			if (sessionId == null || sessionId.isEmpty()) {
				throw new IOException("Odoo auth response contains no session_id "
				        + "(checked Set-Cookie header and JSON body) — HTTP " + response.code());
			}

			log.info("[BedOrder] Authentication succeeded — session_id obtained");
			return sessionId;
		}
	}

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
	 * Authenticates with Odoo, then POSTs the bed-nights sale payload to the same /api/bdsec/sales
	 * endpoint used for consultation fees. Fails fast — before contacting Odoo at all — if the bed
	 * is already reserved by a different patient, so no orphaned Odoo sale order is created for a
	 * doomed request.
	 */
	public SimpleObject submitBedQuotation(
	        String patientId, String patientUuid, String visitUuid,
	        Integer bedId, String bedNumber, String wardUuid, String roomName, int numberOfNights,
	        String paymentMethod, String modeOfPayment,
	        Object voided, String dateCreated, String dateChanged, String createdBy) {

		SimpleObject conflict = checkBedAlreadyReserved(bedId, bedNumber, patientId);
		if (conflict != null) {
			return conflict;
		}

		String odooEndpoint = getOdooBaseUrl() + getSalesOdooPath();
		int shopId = getShopId();

		log.info("[BedOrder] Preparing Odoo payload"
		        + " — endpoint=" + odooEndpoint
		        + " patient_unique_id=" + patientId
		        + " patientUuid=" + patientUuid
		        + " VisitUuid=" + visitUuid
		        + " Payment_method=" + paymentMethod
		        + " Payment_type=" + modeOfPayment
		        + " shop_id=" + shopId
		        + " default_code=" + bedNumber + " quantity=" + numberOfNights);

		String salesBody = OdooSalesPayloadBuilder.buildSalesPayloadJson(
		        patientId, patientUuid, visitUuid, shopId,
		        paymentMethod, modeOfPayment,
		        voided, dateCreated, dateChanged, createdBy,
		        bedNumber, numberOfNights);

		log.info("[BedOrder] Payload to Odoo: " + salesBody);

		OkHttpClient client = new OkHttpClient.Builder()
		        .connectTimeout(getConnectTimeoutSeconds(), TimeUnit.SECONDS)
		        .readTimeout(getReadTimeoutSeconds(), TimeUnit.SECONDS)
		        .writeTimeout(getReadTimeoutSeconds(), TimeUnit.SECONDS)
		        .build();

		int maxAttempts = getMaxRetries() + 1;
		IOException lastError = null;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				log.info("[BedOrder] Attempt " + attempt + "/" + maxAttempts
				        + " — authenticating with Odoo for patientUuid=" + patientUuid);
				String sessionId = authenticate(client);
				return postSalesOrder(client, sessionId, salesBody, odooEndpoint,
				        patientId, patientUuid, visitUuid, bedId, bedNumber, wardUuid, roomName);
			}
			catch (IOException e) {
				lastError = e;
				boolean retryable = isRetryableConnectionError(e) && attempt < maxAttempts;
				log.warn("[BedOrder] Attempt " + attempt + " failed for patientUuid=" + patientUuid
				        + " — " + e.getMessage() + (retryable ? " — retrying after backoff" : " — giving up"));
				if (retryable) {
					sleepBeforeRetry(attempt);
					continue;
				}
				break;
			}
		}

		log.error("[BedOrder] Bed quotation to Odoo FAILED after " + maxAttempts
		        + " attempt(s) for patientUuid=" + patientUuid + ": "
		        + (lastError != null ? lastError.getMessage() : "unknown error"), lastError);

		SimpleObject result = new SimpleObject();
		result.put("status", "error");
		result.put("errorType", "patient_sync_failed");
		result.put("patientSynced", false);
		result.put("message", "Unable to reach the billing system (Odoo) to raise the bed quotation after "
		        + maxAttempts + " attempt(s): " + (lastError != null ? lastError.getMessage() : "unknown error"));
		result.put("patientUuid", patientUuid);
		result.put("VisitUuid", visitUuid);
		return result;
	}

	/**
	 * Returns a structured error response if {@code bedId} already has an active (PENDING/PAID/
	 * WAIVED, non-voided) reservation belonging to a different patient — null when the bed is free
	 * to reserve (no record, a CANCELLED record, or an existing record for the SAME patient).
	 */
	private SimpleObject checkBedAlreadyReserved(Integer bedId, String bedNumber, String patientId) {
		if (bedId == null) {
			return null;
		}
		OdooBillingPaymentStatus existing =
		        Context.getService(OdooBillingPaymentStatusService.class).getLatestByBedId(bedId);

		if (existing == null || !ACTIVE_RESERVATION_STATUSES.contains(existing.getPaymentStatus())) {
			return null;
		}
		if (existing.getPatientId() != null && existing.getPatientId().equals(patientId)) {
			return null;
		}

		log.warn("[BedOrder] Bed already reserved — bedId=" + bedId + " bedNumber=" + bedNumber
		        + " reservedFor=" + existing.getPatientId() + " status=" + existing.getPaymentStatus()
		        + " requestedFor=" + patientId);

		SimpleObject result = new SimpleObject();
		result.put("status", "error");
		result.put("errorType", "bed_already_reserved");
		result.put("message", "This bed has already been reserved by another patient and is awaiting payment or admission.");
		result.put("bedId", bedId);
		result.put("bedNumber", bedNumber);
		return result;
	}

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
	 * Posts the sales payload to Odoo and classifies the result — identical classification logic
	 * to ConsultationFeeOdooService.postSalesOrder(), since it's the same Odoo endpoint/response
	 * shape, just for a bed line item instead of a consultation line item.
	 */
	private SimpleObject postSalesOrder(OkHttpClient client, String sessionId, String salesBody, String odooEndpoint,
	        String patientId, String patientUuid, String visitUuid, Integer bedId, String bedNumber,
	        String wardUuid, String roomName) throws IOException {

		Request salesRequest = new Request.Builder()
		        .url(odooEndpoint)
		        .post(RequestBody.create(salesBody, JSON))
		        .addHeader("Content-Type", "application/json")
		        .addHeader("Cookie", "session_id=" + sessionId)
		        .build();

		try (Response salesResponse = client.newCall(salesRequest).execute()) {
			String responseBody = salesResponse.body() != null ? salesResponse.body().string() : "";
			log.info("[BedOrder] Odoo sales response — HTTP " + salesResponse.code() + " body: " + responseBody);

			SimpleObject result = new SimpleObject();
			result.put("odooResponseCode", salesResponse.code());
			result.put("patientUuid", patientUuid);
			result.put("VisitUuid", visitUuid);
			result.put("bedId", bedId);
			result.put("bedNumber", bedNumber);

			SimpleObject odooResult = null;
			try {
				odooResult = SimpleObject.parseJson(responseBody);
				surfaceOdooResponseFields(odooResult, result);
			}
			catch (Exception parseEx) {
				log.warn("[BedOrder] Could not parse Odoo response as JSON: " + parseEx.getMessage());
			}

			boolean patientSynced = odooResult != null && (odooResult.containsKey("patient_id")
			        || odooResult.containsKey("patient_name") || odooResult.containsKey("bahmni_patient_id"));
			boolean orderCreated = odooResult != null
			        && (odooResult.containsKey("sale_order_id") || odooResult.containsKey("sale_order_name"));
			boolean odooStructuredError = odooResult != null
			        && (odooResult.containsKey("error") || odooResult.containsKey("status"));

			result.put("patientSynced", patientSynced);

			if (patientSynced && orderCreated && salesResponse.isSuccessful()) {
				log.info("[BedOrder] Patient synced and bed sale order created successfully"
				        + " — sale_order_id=" + result.get("sale_order_id")
				        + " sale_order_name=" + result.get("sale_order_name")
				        + " bedNumber=" + bedNumber);
				replicatePendingPaymentStatus(odooResult, patientId, patientUuid, visitUuid, bedId, bedNumber, wardUuid, roomName);
			}
			else if (patientSynced) {
				result.put("status", "error");
				result.put("errorType", "order_failed");
				ensureMessage(result, odooResult, "Patient synchronized, but the bed order could not be created.");
				log.warn("[BedOrder] Patient synced but ORDER creation FAILED — patientUuid=" + patientUuid
				        + " message=" + result.get("message"));
			}
			else if (odooStructuredError) {
				result.put("status", "error");
				result.put("errorType", "order_failed");
				ensureMessage(result, odooResult, "Odoo rejected the bed order (HTTP " + salesResponse.code() + ").");
				log.warn("[BedOrder] Odoo rejected the ORDER (structured error, patient status unknown) —"
				        + " patientId=" + patientId + " patientUuid=" + patientUuid
				        + " httpCode=" + salesResponse.code() + " message=" + result.get("message"));
			}
			else {
				result.put("status", "error");
				result.put("errorType", "patient_sync_failed");
				ensureMessage(result, odooResult, "Patient record could not be synchronized with the billing system "
				        + "(Odoo HTTP " + salesResponse.code() + ").");
				log.warn("[BedOrder] Patient sync FAILED — patientId=" + patientId
				        + " patientUuid=" + patientUuid + " httpCode=" + salesResponse.code());
			}

			return result;
		}
	}

	/**
	 * Replicates the successful Odoo sales response into odoo_billing_payment_status — the same
	 * persistence path used for consultation fees — with serviceType=BED and the bed_id/bed_number
	 * columns set, so the bed shows as reserved immediately. Best-effort: any failure here is
	 * logged and swallowed so it can never affect the response already being returned to the
	 * Bahmni frontend.
	 */
	private void replicatePendingPaymentStatus(SimpleObject odooResult, String patientId, String patientUuid,
	        String visitUuid, Integer bedId, String bedNumber, String wardUuid, String roomName) {
		try {
			Map<String, Object> billingPayload = new HashMap<>();
			billingPayload.put("sale_id", odooResult.get("sale_order_id"));
			billingPayload.put("sale_name", odooResult.get("sale_order_name"));
			billingPayload.put("patient_id", patientId);
			billingPayload.put("patient_uuid", patientUuid);
			billingPayload.put("visit_uuid", visitUuid);
			billingPayload.put("payment_status", "Pending");
			billingPayload.put("bed_id", bedId);
			billingPayload.put("bed_number", bedNumber);
			billingPayload.put("ward_uuid", wardUuid);
			billingPayload.put("room_name", roomName);
			Object paymentType = odooResult.get("payment_type");
			if (paymentType != null) {
				billingPayload.put("customer_type", paymentType);
			}
			billingPayload.put("services",
			        Collections.singletonList(Collections.singletonMap("serviceType", "Bed")));

			SimpleObject billingResult = billingOrderService.processBillingOrder(billingPayload);
			log.info("[BedOrder] Replicated Odoo sales response to odoo_billing_payment_status as PENDING"
			        + " — sale_order_id=" + odooResult.get("sale_order_id") + " bedNumber=" + bedNumber
			        + " result=" + billingResult);
		}
		catch (Exception e) {
			log.warn("[BedOrder] Failed to replicate Odoo sales response to odoo_billing_payment_status"
			        + " — patientUuid=" + patientUuid + " bedNumber=" + bedNumber + ": " + e.getMessage(), e);
		}
	}

	// ---------- Ward overlay + cancellation ----------

	/**
	 * Returns the set of ward UUIDs and (wardUuid, roomName) pairs that currently have at least
	 * one active (PENDING/PAID/WAIVED) bed reservation **belonging to the given patient** — so the
	 * ward list and the room tabs within a ward can show "your patient's reservation is in here"
	 * without a clinician having to open every ward to find it. Deliberately scoped to one
	 * patient, not a global "anyone has a reservation here" view — that would surface other
	 * patients' reservations as noise (or worse, a privacy leak) on a screen that's otherwise
	 * entirely about the one patient currently selected.
	 *
	 * Computed in Java rather than SQL: fetches every BED-service record (small dataset for any
	 * realistic deployment), groups by bedId, and keeps only the latest row per bed — the same
	 * "latest row wins" rule used everywhere else in this append-only table.
	 */
	public SimpleObject getActiveReservationLocations(String patientId) {
		Set<String> wardUuids = new LinkedHashSet<>();
		Set<String> roomKeys = new LinkedHashSet<>();
		List<SimpleObject> rooms = new ArrayList<>();

		if (patientId == null || patientId.isEmpty()) {
			SimpleObject empty = new SimpleObject();
			empty.put("wardUuids", new ArrayList<>(wardUuids));
			empty.put("rooms", rooms);
			return empty;
		}

		List<OdooBillingPaymentStatus> records =
		        Context.getService(OdooBillingPaymentStatusService.class).getAllBedServiceRecords();

		Integer currentBedId = null;
		for (OdooBillingPaymentStatus record : records) {
			// getAllBedServiceRecords() is ordered by (bedId, id desc) — the first row seen for
			// each bedId is therefore its latest, every subsequent row for the same bedId is older
			// history and must be skipped.
			if (record.getBedId().equals(currentBedId)) {
				continue;
			}
			currentBedId = record.getBedId();

			if (!ACTIVE_RESERVATION_STATUSES.contains(record.getPaymentStatus())) {
				continue;
			}
			if (record.getWardUuid() == null || !patientId.equals(record.getPatientId())) {
				continue;
			}

			wardUuids.add(record.getWardUuid());
			String roomKey = record.getWardUuid() + "|" + record.getRoomName();
			if (record.getRoomName() != null && roomKeys.add(roomKey)) {
				SimpleObject room = new SimpleObject();
				room.put("wardUuid", record.getWardUuid());
				room.put("roomName", record.getRoomName());
				rooms.add(room);
			}
		}

		SimpleObject result = new SimpleObject();
		result.put("wardUuids", new ArrayList<>(wardUuids));
		result.put("rooms", rooms);
		return result;
	}

	/**
	 * For each bed id in {@code bedIds} that has an active (PENDING/PAID/WAIVED) reservation,
	 * returns {bedId, bedNumber, patientId, patientName, reservationStatus, createdAt}.
	 * Used by the ward bed grid to render the reservation badge/popover.
	 */
	public List<SimpleObject> getReservationsForBeds(List<Integer> bedIds) {
		OdooBillingPaymentStatusService service = Context.getService(OdooBillingPaymentStatusService.class);
		List<SimpleObject> reservations = new ArrayList<>();

		for (Integer bedId : bedIds) {
			OdooBillingPaymentStatus record = service.getLatestByBedId(bedId);
			if (record == null || !ACTIVE_RESERVATION_STATUSES.contains(record.getPaymentStatus())) {
				continue;
			}

			SimpleObject reservation = new SimpleObject();
			reservation.put("bedId", bedId);
			reservation.put("bedNumber", record.getBedNumber());
			reservation.put("patientId", record.getPatientId());
			reservation.put("patientName", resolvePatientName(record.getPatientId()));
			reservation.put("reservationStatus", "PENDING".equals(record.getPaymentStatus()) ? "PENDING_PAYMENT" : "PAID");
			reservation.put("createdAt", record.getCreatedAt());
			reservations.add(reservation);
		}
		return reservations;
	}

	private String resolvePatientName(String patientIdentifier) {
		if (patientIdentifier == null) {
			return null;
		}
		Context.addProxyPrivilege("Get Patients");
		try {
			List<Patient> patients =
			        Context.getPatientService().getPatients(null, patientIdentifier, null, true);
			return !patients.isEmpty() ? patients.get(0).getPersonName().getFullName() : null;
		}
		finally {
			Context.removeProxyPrivilege("Get Patients");
		}
	}

	/**
	 * Cancels the active reservation on {@code bedId} (if any) by appending a new CANCELLED row —
	 * same append-only history mechanism as every other status transition on this table. Writes
	 * directly via OdooBillingPaymentStatusService rather than BillingOrderService.processBillingOrder,
	 * since the existing record already carries the resolved patientId/visitId and there is no
	 * patient_uuid/visit_uuid round-trip needed here.
	 */
	public SimpleObject cancelReservation(Integer bedId, String reason) {
		OdooBillingPaymentStatusService service = Context.getService(OdooBillingPaymentStatusService.class);
		OdooBillingPaymentStatus existing = service.getLatestByBedId(bedId);

		SimpleObject result = new SimpleObject();
		if (existing == null || !ACTIVE_RESERVATION_STATUSES.contains(existing.getPaymentStatus())) {
			result.put("status", "error");
			result.put("message", "No active reservation found for bedId=" + bedId);
			return result;
		}

		OdooBillingPaymentStatusDTO dto = new OdooBillingPaymentStatusDTO();
		dto.setPatientId(existing.getPatientId());
		dto.setVisitId(existing.getVisitId());
		dto.setServiceType(existing.getServiceType());
		dto.setServiceReferenceId(existing.getServiceReferenceId());
		dto.setOdooInvoiceId(existing.getOdooInvoiceId());
		dto.setBedId(existing.getBedId());
		dto.setBedNumber(existing.getBedNumber());
		dto.setCurrency(existing.getCurrency());
		dto.setPaymentStatus("CANCELLED");

		service.saveOrUpdatePaymentStatus(dto);

		log.info("[BedOrder] Reservation cancelled — bedId=" + bedId + " bedNumber=" + existing.getBedNumber()
		        + " previousPatientId=" + existing.getPatientId() + " reason=" + reason);

		result.put("status", "success");
		result.put("message", "Reservation cancelled for bed " + existing.getBedNumber());
		result.put("bedId", bedId);
		return result;
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
}
