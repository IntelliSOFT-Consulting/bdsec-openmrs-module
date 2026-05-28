package org.openmrs.module.odooconnector.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.odooconnector.web.service.ConsultationFeeOdooService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;

/**
 * REST endpoint: POST /openmrs/ws/rest/v1/odooconnector/consultation-fee
 *
 * Receives the consultation payload from the Bahmni registration second page (visitController.js),
 * validates required fields, then delegates to ConsultationFeeOdooService which authenticates
 * with Odoo and forwards the payload to /api/bdsec/sales.
 *
 * Called non-blocking after encounter creation — a failure here must not prevent the
 * registration save from completing on the Bahmni side.
 */
@Controller
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/odooconnector/consultation-fee")
public class ConsultationFeeController {

	protected final Log log = LogFactory.getLog(getClass());

	@Autowired
	private ConsultationFeeOdooService consultationFeeOdooService;

	@RequestMapping(method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public @ResponseBody SimpleObject postConsultationFee(@RequestBody Map<String, Object> body) {

		log.info("[ConsultationFee] Received payload from Bahmni registration: " + body);

		// --- Required field validation ---
		String patientUuid = (String) body.get("patientUuid");
		if (patientUuid == null || patientUuid.isEmpty()) {
			log.warn("[ConsultationFee] Rejected — patientUuid is missing.");
			return errorResponse("patientUuid is required");
		}

		String currentVisitUuid = (String) body.get("currentVisitUuid");
		if (currentVisitUuid == null || currentVisitUuid.isEmpty()) {
			log.warn("[ConsultationFee] Rejected — currentVisitUuid is missing.");
			return errorResponse("currentVisitUuid is required");
		}

		// --- Optional / defaulted fields ---
		String patientId     = (String) body.get("patientId");
		String paymentMethod = (String) body.get("paymentMethod");
		String modeOfPayment = (String) body.get("modeOfPayment");
		Object voided        = body.getOrDefault("voided", false);
		String dateCreated   = (String) body.get("dateCreated");
		String dateChanged   = (String) body.get("dateChanged");
		String createdBy     = (String) body.get("createdBy");

		log.info("[ConsultationFee] Validation passed — forwarding to Odoo"
		        + " patientId=" + patientId
		        + " patientUuid=" + patientUuid
		        + " visitUuid=" + currentVisitUuid
		        + " paymentMethod=" + paymentMethod
		        + " modeOfPayment=" + modeOfPayment
		        + " voided=" + voided
		        + " createdBy=" + createdBy);

		return consultationFeeOdooService.forwardConsultationFee(
		        patientId, patientUuid, currentVisitUuid,
		        paymentMethod, modeOfPayment,
		        voided, dateCreated, dateChanged, createdBy);
	}

	private SimpleObject errorResponse(String message) {
		SimpleObject error = new SimpleObject();
		error.put("status", "rejected");
		error.put("error", message);
		return error;
	}
}
