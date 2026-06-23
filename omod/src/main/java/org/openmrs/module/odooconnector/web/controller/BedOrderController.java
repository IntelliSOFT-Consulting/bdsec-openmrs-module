package org.openmrs.module.odooconnector.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.odooconnector.web.service.BedOrderOdooService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the bed-reservation ("Submit Quotation") workflow on the admission screen.
 *
 * POST /openmrs/ws/rest/v1/odooconnector/bed-order
 *   Raises a bed-nights sale order in Odoo (same /api/bdsec/sales endpoint as consultation fees)
 *   and, on success, records it in odoo_billing_payment_status (serviceType=BED) so the bed shows
 *   as reserved and the Admit button's payment gate can find it.
 *
 * GET /openmrs/ws/rest/v1/odooconnector/bed-order/reservations?bedIds=1,2,3
 *   Returns the active reservation (if any) for each requested bed id — used to render the
 *   reservation badge/popover on the ward bed grid.
 *
 * POST /openmrs/ws/rest/v1/odooconnector/bed-order/cancel
 *   Cancels the active reservation on a bed, releasing it for selection again.
 */
@Controller
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/odooconnector/bed-order")
public class BedOrderController {

	protected final Log log = LogFactory.getLog(getClass());

	@Autowired
	private BedOrderOdooService bedOrderOdooService;

	@RequestMapping(method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public @ResponseBody SimpleObject submitBedQuotation(@RequestBody Map<String, Object> body) {

		log.info("[BedOrder] Received bed quotation request from Bahmni frontend: " + body);

		String patientId   = (String) body.get("patientId");
		String patientUuid = (String) body.get("patientUuid");
		String visitUuid   = (String) body.get("visitUuid");
		String bedNumber   = (String) body.get("bedNumber");
		Integer bedId      = toInteger(body.get("bedId"));
		String wardUuid    = (String) body.get("wardUuid");
		String roomName    = (String) body.get("roomName");
		int numberOfNights = toInteger(body.get("numberOfNights")) != null ? toInteger(body.get("numberOfNights")) : 0;

		if (patientUuid == null || patientUuid.isEmpty()) {
			return errorResponse("patientUuid is required");
		}
		if (visitUuid == null || visitUuid.isEmpty()) {
			return errorResponse("visitUuid is required");
		}
		if (bedId == null || bedNumber == null || bedNumber.isEmpty()) {
			return errorResponse("bedId and bedNumber are required");
		}
		if (numberOfNights <= 0) {
			return errorResponse("numberOfNights must be greater than 0");
		}

		String paymentMethod = (String) body.get("paymentMethod");
		String modeOfPayment = (String) body.get("modeOfPayment");
		Object voided         = body.getOrDefault("voided", false);
		String dateCreated    = (String) body.get("dateCreated");
		String dateChanged    = (String) body.get("dateChanged");
		String createdBy      = (String) body.get("createdBy");

		log.info("[BedOrder] Validation passed — forwarding to Odoo"
		        + " patientId=" + patientId + " patientUuid=" + patientUuid + " visitUuid=" + visitUuid
		        + " bedId=" + bedId + " bedNumber=" + bedNumber + " numberOfNights=" + numberOfNights);

		return bedOrderOdooService.submitBedQuotation(
		        patientId, patientUuid, visitUuid,
		        bedId, bedNumber, wardUuid, roomName, numberOfNights,
		        paymentMethod, modeOfPayment,
		        voided, dateCreated, dateChanged, createdBy);
	}

	@RequestMapping(value = "/reservations/locations", method = RequestMethod.GET)
	public @ResponseBody SimpleObject getReservationLocations(
	        @RequestParam(value = "patientId", required = false) String patientId) {
		return bedOrderOdooService.getActiveReservationLocations(patientId);
	}

	@RequestMapping(value = "/reservations/patient-active", method = RequestMethod.GET)
	public @ResponseBody SimpleObject getActivePatientBedReservation(
	        @RequestParam(value = "patientId", required = false) String patientId) {
		return bedOrderOdooService.getActivePatientBedReservation(patientId);
	}

	@RequestMapping(value = "/reservations", method = RequestMethod.GET)
	public @ResponseBody SimpleObject getReservations(@RequestParam("bedIds") String bedIdsCsv) {
		List<Integer> bedIds = new ArrayList<>();
		for (String part : bedIdsCsv.split(",")) {
			try {
				bedIds.add(Integer.parseInt(part.trim()));
			}
			catch (NumberFormatException ignored) {}
		}
		SimpleObject result = new SimpleObject();
		result.put("reservations", bedOrderOdooService.getReservationsForBeds(bedIds));
		return result;
	}

	@RequestMapping(value = "/cancel", method = RequestMethod.POST)
	public @ResponseBody SimpleObject cancelReservation(@RequestBody Map<String, Object> body) {
		Integer bedId = toInteger(body.get("bedId"));
		String reason = (String) body.get("reason");
		if (bedId == null) {
			return errorResponse("bedId is required");
		}
		log.info("[BedOrder] Cancel reservation request — bedId=" + bedId + " reason=" + reason);
		return bedOrderOdooService.cancelReservation(bedId, reason);
	}

	private Integer toInteger(Object value) {
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value instanceof String && !((String) value).isEmpty()) {
			try {
				return Integer.parseInt((String) value);
			}
			catch (NumberFormatException ignored) {}
		}
		return null;
	}

	private SimpleObject errorResponse(String message) {
		SimpleObject error = new SimpleObject();
		error.put("status", "rejected");
		error.put("error", message);
		return error;
	}
}
