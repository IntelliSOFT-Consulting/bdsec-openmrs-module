package org.openmrs.module.odooconnector.web.service;

/**
 * Builds the JSON payload required by Odoo's /api/bdsec/sales endpoint. This shape is shared
 * verbatim by every billable component (consultation fee, bed nights, ...) — the only fields
 * that differ per use case are lines[0].default_code and lines[0].quantity, both supplied by
 * the caller.
 */
public final class OdooSalesPayloadBuilder {

	private OdooSalesPayloadBuilder() {}

	public static String buildSalesPayloadJson(
	        String patientId, String patientUuid, String visitUuid, int shopId,
	        String paymentMethod, String modeOfPayment,
	        Object voided, String dateCreated, String dateChanged, String createdBy,
	        String defaultCode, int quantity) {

		String safePatientId     = patientId != null ? escapeJson(patientId) : "";
		String safePatientUuid   = patientUuid != null ? escapeJson(patientUuid) : "";
		String safeVisitUuid     = visitUuid != null ? escapeJson(visitUuid) : "";
		String safePaymentMethod = paymentMethod != null ? escapeJson(paymentMethod) : "free";
		String safeModeOfPayment = modeOfPayment != null ? escapeJson(modeOfPayment) : "";
		String safeDateCreated   = dateCreated != null ? escapeJson(dateCreated) : "";
		String safeDateChanged   = dateChanged != null ? escapeJson(dateChanged) : "";
		String safeCreatedBy     = createdBy != null ? escapeJson(createdBy) : "";
		String safeDefaultCode   = defaultCode != null ? escapeJson(defaultCode) : "";
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
		        + "\"lines\":[{\"default_code\":\"" + safeDefaultCode + "\",\"quantity\":" + quantity + "}]"
		        + "}";
	}

	/** Escapes backslashes and double-quotes so the value is safe inside a JSON string. */
	private static String escapeJson(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
