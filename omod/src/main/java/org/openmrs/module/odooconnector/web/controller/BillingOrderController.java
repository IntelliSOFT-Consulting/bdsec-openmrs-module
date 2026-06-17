package org.openmrs.module.odooconnector.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.odooconnector.web.service.BillingOrderService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * REST endpoint: POST /openmrs/ws/rest/v1/odooconnector/billing/orders
 *
 * Receives fully-paid or pending billing orders pushed from Odoo and persists
 * them via OdooBillingPaymentStatusService so the Bahmni billing gate can check
 * payment state for each patient visit.
 *
 * Authentication (one of the following headers is required):
 *   Authorization: Bearer <token>   (preferred)
 *   x-api-key: <key>
 *
 * Both values are configured via OpenMRS Global Properties:
 *   odooconnector.billing.inboundBearerToken
 *   odooconnector.billing.inboundApiKey
 *
 * Nginx proxy note: to expose this at the spec URL /api/billing/orders, add:
 *   location /api/billing/ {
 *       proxy_pass http://openmrs:8080/openmrs/ws/rest/v1/odooconnector/billing/;
 *   }
 */
@Controller("odooconnector.BillingOrderController")
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/odooconnector/billing/orders",
                produces = MediaType.APPLICATION_JSON_VALUE)
public class BillingOrderController {

    private static final Log log = LogFactory.getLog(BillingOrderController.class);

    @Autowired
    private BillingOrderService billingOrderService;

    /**
     * Accepts a billing order from Odoo.
     *
     * Returns 401 when credentials are missing or invalid.
     * Returns 400 when required fields (sale_id, patient_uuid, visit_uuid) are absent
     *         or when the patient/visit UUID cannot be resolved.
     * Returns 200 on success (new or idempotent duplicate).
     */
    @RequestMapping(method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody SimpleObject receiveBillingOrder(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "x-api-key",     required = false) String apiKey,
            @RequestBody Map<String, Object> body,
            HttpServletResponse httpResponse) {

        log.info("[BillingOrder] POST /odooconnector/billing/orders — sale_id=" + body.get("sale_id")
                + " patient_uuid=" + body.get("patient_uuid")
                + " visit_uuid=" + body.get("visit_uuid"));

        if (!billingOrderService.isAuthenticated(authHeader, apiKey)) {
            log.warn("[BillingOrder] 401 Unauthorized — sale_id=" + body.get("sale_id"));
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            SimpleObject err = new SimpleObject();
            err.put("status", "error");
            err.put("message", "Unauthorized — provide a valid Bearer token or x-api-key");
            return err;
        }

        return billingOrderService.processBillingOrder(body, httpResponse);
    }
}
