/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.odooconnector.web;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.odooconnector.api.OdooBillingPaymentStatusService;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

/**
 * Spring MVC interceptor that enforces Odoo payment status before allowing
 * mutating clinical requests to proceed.
 *
 * <p>Intercepted URL patterns (POST / PUT only):
 * <ul>
 *   <li>{@code *​/encounter/**}  — consultations and ward encounters</li>
 *   <li>{@code *​/order/**}      — lab orders and medication prescriptions</li>
 *   <li>{@code *​/bedassignment/**} — inpatient bed assignment</li>
 *   <li>{@code *​/bedmanagement/**} — bed management actions</li>
 * </ul>
 *
 * <p>The service type is inferred from the URL segment. patientId and visitId
 * are read from request parameters ({@code patientId} / {@code visitId}).
 * If the patient is identified only by UUID ({@code patientUuid} param) or
 * the visit by UUID ({@code visitUuid}), those are resolved via OpenMRS Context.
 *
 * <p>A comma-separated bypass list can be maintained via the OpenMRS Global
 * Property {@code odooconnector.billing.emergencyBypassPatients} (patient UUIDs).
 * Set {@code odooconnector.billing.gatEnabled=false} to disable the gate globally
 * (useful during initial rollout or emergencies).
 *
 * <p>Every check emits a log line prefixed with {@code [BillingGate]} for
 * easy log filtering.
 */
public class BillingPaymentGateInterceptor extends HandlerInterceptorAdapter {

    private static final Log log = LogFactory.getLog(BillingPaymentGateInterceptor.class);

    private static final String GP_GATE_ENABLED       = "odooconnector.billing.gateEnabled";
    private static final String GP_BYPASS_PATIENTS    = "odooconnector.billing.emergencyBypassPatients";

    /** URL fragments that trigger the gate and their mapped service types. */
    private static final List<String[]> SERVICE_PATTERNS = Arrays.asList(
            new String[]{ "/encounter",    "CONSULTATION" },
            new String[]{ "/bahmniencounter", "CONSULTATION" },
            new String[]{ "/order",        "LAB_ORDER" },
            new String[]{ "/drugorder",    "MEDICATION" },
            new String[]{ "/bedassignment","BED" },
            new String[]{ "/bedmanagement","BED" }
    );

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            return true;
        }

        String uri = request.getRequestURI();
        String serviceType = resolveServiceType(uri);
        if (serviceType == null) {
            return true;
        }

        AdministrationService adminService = Context.getAdministrationService();

        // Global kill-switch (defaults to enabled when property absent)
        String gateEnabledGP = adminService.getGlobalProperty(GP_GATE_ENABLED, "true");
        if (!"true".equalsIgnoreCase(gateEnabledGP.trim())) {
            log.debug("[BillingGate] Gate is disabled via global property — request allowed uri=" + uri);
            return true;
        }

        String  patientIdentifier = PatientVisitResolver.resolvePatientIdentifier(
                request.getParameter("patientId"), request.getParameter("patientUuid"));
        Integer visitId           = PatientVisitResolver.resolveVisitId(
                request.getParameter("visitId"), request.getParameter("visitUuid"));

        if (patientIdentifier == null || visitId == null) {
            log.warn("[BillingGate] Could not resolve patient identifier or visitId — allowing request to proceed uri=" + uri
                    + " patientIdentifier=" + patientIdentifier + " visitId=" + visitId);
            return true;
        }

        // Per-patient emergency bypass list
        if (isPatientBypassed(patientIdentifier, adminService)) {
            log.info("[BillingGate] BYPASS — patient=" + patientIdentifier + " is on emergency bypass list uri=" + uri);
            return true;
        }

        log.info("[BillingGate] Gate check triggered — patient=" + patientIdentifier
                + " visit=" + visitId
                + " service=" + serviceType
                + " uri=" + uri);

        OdooBillingPaymentStatusService billingService =
                Context.getService(OdooBillingPaymentStatusService.class);

        boolean paid = billingService.isServicePaid(patientIdentifier, visitId, serviceType);

        if (paid) {
            log.info("[BillingGate] ALLOWED — patient=" + patientIdentifier
                    + " visit=" + visitId
                    + " service=" + serviceType);
            return true;
        }

        log.warn("[BillingGate] BLOCKED — patient=" + patientIdentifier
                + " visit=" + visitId
                + " service=" + serviceType
                + " uri=" + uri);

        // Write HTTP 402 directly — @ResponseStatus is not available in the api module
        response.setStatus(402);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"error\":\"Payment Required\""
                + ",\"message\":\"Payment required for service " + serviceType + "\""
                + ",\"patientId\":\"" + patientIdentifier + "\""
                + ",\"visitId\":" + visitId
                + ",\"serviceType\":\"" + serviceType + "\"}");
        response.getWriter().flush();
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolveServiceType(String uri) {
        for (String[] pattern : SERVICE_PATTERNS) {
            if (uri.contains(pattern[0])) {
                return pattern[1];
            }
        }
        return null;
    }

    private boolean isPatientBypassed(String patientIdentifier, AdministrationService adminService) {
        String bypassList = adminService.getGlobalProperty(GP_BYPASS_PATIENTS, "");
        if (StringUtils.isBlank(bypassList)) {
            return false;
        }
        // Bypass list is stored as comma-separated Bahmni patient identifiers (e.g. ABC200001,ABC200002)
        for (String entry : bypassList.split(",")) {
            if (entry.trim().equals(patientIdentifier)) {
                return true;
            }
        }
        return false;
    }
}
