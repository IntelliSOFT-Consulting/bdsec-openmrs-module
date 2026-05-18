/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.odooconnector.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatusDTO;
import org.openmrs.module.odooconnector.api.OdooBillingPaymentStatusService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * REST controller exposing Odoo billing payment status endpoints.
 *
 * <pre>
 * POST   /openmrs/ws/rest/v1/odoo/billing/payment-status
 *        — receive a payment update pushed from Odoo
 *
 * GET    /openmrs/ws/rest/v1/odoo/billing/payment-status
 *        ?patientId=&amp;visitId=&amp;serviceType=
 *        — return the current payment record for a service
 *
 * GET    /openmrs/ws/rest/v1/odoo/billing/payment-status/visit/{visitId}
 *        — return all services (and statuses) for a visit
 *
 * GET    /openmrs/ws/rest/v1/odoo/billing/is-paid
 *        ?patientId=&amp;visitId=&amp;serviceType=
 *        — boolean payment gate check used by BillingPaymentGateInterceptor
 * </pre>
 */
@Controller("odooconnector.OdooBillingPaymentStatusController")
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/odoo/billing")
public class OdooBillingPaymentStatusController {

    private static final Log log = LogFactory.getLog(OdooBillingPaymentStatusController.class);

    // -------------------------------------------------------------------------
    // POST /odoo/billing/payment-status
    // -------------------------------------------------------------------------

    /**
     * Receive a payment status record pushed from Odoo.
     * Creates or updates the record for the (patientId, visitId, serviceType) combination.
     */
    @RequestMapping(
            value = "/payment-status",
            method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public @ResponseBody SimpleObject receivePaymentUpdate(@RequestBody OdooBillingPaymentStatusDTO dto) {
        log.info("[OdooBilling] POST /odoo/billing/payment-status — patient=" + dto.getPatientId()
                + " visit=" + dto.getVisitId()
                + " service=" + dto.getServiceType()
                + " status=" + dto.getPaymentStatus());

        OdooBillingPaymentStatus saved =
                Context.getService(OdooBillingPaymentStatusService.class)
                       .saveOrUpdatePaymentStatus(dto);

        log.info("[OdooBilling] Upsert result — id=" + saved.getId() + " uuid=" + saved.getUuid());

        return toSimpleObject(saved);
    }

    // -------------------------------------------------------------------------
    // GET /odoo/billing/payment-status?patientId=&visitId=&serviceType=
    // -------------------------------------------------------------------------

    /**
     * Returns the current payment record for a specific patient/visit/service.
     * Returns HTTP 404 body ({@code found:false}) when no record exists yet.
     */
    @RequestMapping(
            value = "/payment-status",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody SimpleObject getPaymentStatus(
            @RequestParam Integer patientId,
            @RequestParam Integer visitId,
            @RequestParam String serviceType) {

        log.info("[OdooBilling] GET /odoo/billing/payment-status — patient=" + patientId
                + " visit=" + visitId + " service=" + serviceType);

        OdooBillingPaymentStatus record =
                Context.getService(OdooBillingPaymentStatusService.class)
                       .getPaymentStatus(patientId, visitId, serviceType);

        if (record == null) {
            SimpleObject notFound = new SimpleObject();
            notFound.put("found", false);
            notFound.put("patientId", patientId);
            notFound.put("visitId", visitId);
            notFound.put("serviceType", serviceType);
            return notFound;
        }

        return toSimpleObject(record);
    }

    // -------------------------------------------------------------------------
    // GET /odoo/billing/payment-status/visit/{visitId}
    // -------------------------------------------------------------------------

    /**
     * Returns all non-voided payment records for the given visit.
     */
    @RequestMapping(
            value = "/payment-status/visit/{visitId}",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody SimpleObject getServicesForVisit(@PathVariable Integer visitId) {
        log.info("[OdooBilling] GET /odoo/billing/payment-status/visit/" + visitId);

        List<OdooBillingPaymentStatus> records =
                Context.getService(OdooBillingPaymentStatusService.class)
                       .getPendingServicesForVisit(visitId);

        List<SimpleObject> results = new ArrayList<>();
        for (OdooBillingPaymentStatus r : records) {
            results.add(toSimpleObject(r));
        }

        SimpleObject response = new SimpleObject();
        response.put("visitId", visitId);
        response.put("count", results.size());
        response.put("results", results);
        return response;
    }

    // -------------------------------------------------------------------------
    // GET /odoo/billing/is-paid?patientId=&visitId=&serviceType=
    // -------------------------------------------------------------------------

    /**
     * Lightweight boolean gate check. Returns {@code {"paid": true|false}}.
     * Called by the front-end and by {@code BillingPaymentGateInterceptor}.
     */
    @RequestMapping(
            value = "/is-paid",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody SimpleObject isServicePaid(
            @RequestParam Integer patientId,
            @RequestParam Integer visitId,
            @RequestParam String serviceType) {

        log.info("[OdooBilling] GET /odoo/billing/is-paid — patient=" + patientId
                + " visit=" + visitId + " service=" + serviceType);

        boolean paid =
                Context.getService(OdooBillingPaymentStatusService.class)
                       .isServicePaid(patientId, visitId, serviceType);

        if (paid) {
            log.info("[OdooBilling] Gate check result ALLOWED — patient=" + patientId
                    + " visit=" + visitId + " service=" + serviceType);
        } else {
            log.warn("[OdooBilling] Gate check result BLOCKED — patient=" + patientId
                    + " visit=" + visitId + " service=" + serviceType);
        }

        SimpleObject result = new SimpleObject();
        result.put("paid", paid);
        result.put("patientId", patientId);
        result.put("visitId", visitId);
        result.put("serviceType", serviceType);
        return result;
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private SimpleObject toSimpleObject(OdooBillingPaymentStatus r) {
        SimpleObject o = new SimpleObject();
        o.put("id", r.getId());
        o.put("uuid", r.getUuid());
        o.put("patientId", r.getPatientId());
        o.put("visitId", r.getVisitId());
        o.put("encounterId", r.getEncounterId());
        o.put("serviceType", r.getServiceType());
        o.put("serviceReferenceId", r.getServiceReferenceId());
        o.put("odooInvoiceId", r.getOdooInvoiceId());
        o.put("paymentStatus", r.getPaymentStatus());
        o.put("amountDue", r.getAmountDue());
        o.put("amountPaid", r.getAmountPaid());
        o.put("currency", r.getCurrency());
        o.put("paymentDate", r.getPaymentDate());
        o.put("odooSyncTimestamp", r.getOdooSyncTimestamp());
        o.put("createdAt", r.getCreatedAt());
        o.put("updatedAt", r.getUpdatedAt());
        o.put("voided", r.getVoided());
        return o;
    }
}
