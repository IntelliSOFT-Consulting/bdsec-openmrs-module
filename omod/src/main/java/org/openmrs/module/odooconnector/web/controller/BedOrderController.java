package org.openmrs.module.odooconnector.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.odooconnector.web.service.BedOrderOdooService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;

@Controller
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/odooconnector/bed-order")
public class BedOrderController {

	protected final Log log = LogFactory.getLog(getClass());

	@Autowired
	private BedOrderOdooService bedOrderOdooService;

	/**
	 * POST /openmrs/ws/rest/v1/odooconnector/bed-order
	 * Creates a bed invoice in Odoo when a clinician selects a bed.
	 */
	@RequestMapping(method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public @ResponseBody SimpleObject createBedOrder(
	        @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {

		log.info("[BedOrder] Received bed order request from Bahmni frontend: " + body);

		String patientUuid   = (String) body.get("patientUuid");
		String visitUuid     = (String) body.get("visitUuid");
		String bedId         = (String) body.get("bedId");
		String bedNumber     = (String) body.get("bedNumber");
		String wardName      = (String) body.get("wardName");
		Object numberOfDays  = body.get("numberOfDays");
		Object amountPerNight = body.get("amountPerNight");
		Object totalAmount   = body.get("totalAmount");
		String currency      = (String) body.get("currency");
		String modeOfPayment = (String) body.get("modeOfPayment");

		log.info("[BedOrder] Forwarding bed order to Odoo — patientUuid=" + patientUuid
		        + " bedId=" + bedId + " bedNumber=" + bedNumber
		        + " wardName=" + wardName + " numberOfDays=" + numberOfDays
		        + " amountPerNight=" + amountPerNight + " totalAmount=" + totalAmount
		        + " currency=" + currency + " modeOfPayment=" + modeOfPayment);

		return bedOrderOdooService.createBedInvoice(
		        patientUuid, visitUuid, bedId, bedNumber, wardName,
		        numberOfDays, amountPerNight, totalAmount, currency, modeOfPayment);
	}

	/**
	 * GET /openmrs/ws/rest/v1/odooconnector/bed-order/payment-status?patientUuid=&bedId=
	 * Checks with Odoo whether the bed invoice for this patient has been paid.
	 * Returns { "paid": true/false, "invoiceId": "...", "amountPaid": ..., "message": "..." }
	 */
	@RequestMapping(value = "/payment-status", method = RequestMethod.GET)
	public @ResponseBody SimpleObject getPaymentStatus(
	        @RequestParam String patientUuid,
	        @RequestParam String bedId) {

		log.info("[BedOrder] Payment status check request received — patientUuid=" + patientUuid
		        + " bedId=" + bedId);

		try {
			SimpleObject result = bedOrderOdooService.checkPaymentStatus(patientUuid, bedId);
			log.info("[BedOrder] Payment status response from Odoo — paid=" + result.get("paid")
			        + " invoiceId=" + result.get("invoiceId"));
			return result;
		}
		catch (RuntimeException e) {
			log.error("[BedOrder] Failed to reach Odoo during payment check: " + e.getMessage(), e);
			throw new BillingUnavailableException(e.getMessage());
		}
	}
}
