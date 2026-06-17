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
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.context.Context;

/**
 * Shared patient/visit resolution logic used by both {@link BillingPaymentGateInterceptor}
 * (server-side enforcement on encounter/order creation) and
 * {@code OdooBillingPaymentStatusController} (the read-only /is-paid check used by the frontend
 * before navigating to the patient dashboard). Both need the same "accept either an ID or a UUID"
 * flexibility, so the logic lives in one place.
 */
public final class PatientVisitResolver {

    private static final Log log = LogFactory.getLog(PatientVisitResolver.class);

    private PatientVisitResolver() {
    }

    /**
     * Resolves the Bahmni patient identifier string (e.g. "BDSEC200001") — never the internal
     * numeric patient PK — so it matches what is stored in odoo_billing_payment_status.
     *
     * @param patientIdParam   raw {@code patientId} value, if supplied (may already be the
     *                         identifier, or may be a legacy numeric patient PK)
     * @param patientUuidParam raw {@code patientUuid} value, if supplied
     */
    public static String resolvePatientIdentifier(String patientIdParam, String patientUuidParam) {
        if (StringUtils.isNotBlank(patientIdParam) && !StringUtils.isNumeric(patientIdParam)) {
            // Already a Bahmni identifier (non-numeric) — use it directly.
            return patientIdParam;
        }
        if (StringUtils.isNotBlank(patientUuidParam)) {
            try {
                Patient patient = Context.getPatientService().getPatientByUuid(patientUuidParam);
                if (patient != null && patient.getPatientIdentifier() != null) {
                    return patient.getPatientIdentifier().getIdentifier();
                }
            } catch (Exception e) {
                log.warn("[PatientVisitResolver] Could not resolve patient from uuid=" + patientUuidParam, e);
            }
        }
        if (StringUtils.isNumeric(patientIdParam)) {
            // Legacy caller sending the numeric patient_id — resolve it to the Bahmni identifier
            // rather than using the numeric value, since that's what is now stored/compared.
            log.warn("[PatientVisitResolver] Received numeric patientId param=" + patientIdParam
                    + " — resolving to Bahmni identifier");
            try {
                Patient patient = Context.getPatientService().getPatient(Integer.parseInt(patientIdParam));
                if (patient != null && patient.getPatientIdentifier() != null) {
                    return patient.getPatientIdentifier().getIdentifier();
                }
            } catch (Exception e) {
                log.warn("[PatientVisitResolver] Could not resolve patient from numeric patientId="
                        + patientIdParam, e);
            }
        }
        return null;
    }

    /**
     * Resolves an OpenMRS visit integer ID from either a numeric {@code visitId} or a
     * {@code visitUuid}.
     */
    public static Integer resolveVisitId(String visitIdParam, String visitUuidParam) {
        if (StringUtils.isNumeric(visitIdParam)) {
            return Integer.parseInt(visitIdParam);
        }
        if (StringUtils.isNotBlank(visitUuidParam)) {
            try {
                Visit visit = Context.getVisitService().getVisitByUuid(visitUuidParam);
                return visit != null ? visit.getVisitId() : null;
            } catch (Exception e) {
                log.warn("[PatientVisitResolver] Could not resolve visit from uuid=" + visitUuidParam, e);
            }
        }
        return null;
    }
}
