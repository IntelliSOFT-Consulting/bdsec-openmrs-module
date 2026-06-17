/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.odooconnector.api.dao;

import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;

import java.util.List;

/**
 * Data access contract for {@link OdooBillingPaymentStatus}.
 */
public interface OdooBillingPaymentStatusDao {

    OdooBillingPaymentStatus saveOrUpdate(OdooBillingPaymentStatus record);

    OdooBillingPaymentStatus getById(Integer id);

    /**
     * Returns the most recent non-voided record matching (patientId, visitId, serviceType),
     * or null if none exists. patientId is the Bahmni patient identifier string.
     */
    OdooBillingPaymentStatus getByPatientVisitService(String patientId, Integer visitId, String serviceType);

    /**
     * Returns all non-voided records for the given visit, regardless of status.
     */
    List<OdooBillingPaymentStatus> getAllByVisit(Integer visitId);

    /**
     * Returns non-voided records with payment_status = 'PENDING' for the visit.
     */
    List<OdooBillingPaymentStatus> getPendingByVisit(Integer visitId);

    /**
     * Returns the first non-voided record whose service_reference_id matches the given value,
     * or null. General-purpose "any record for this sale_id" lookup — not used for idempotency
     * decisions (see {@link #getLatestByServiceReferenceIdAndStatus}).
     */
    OdooBillingPaymentStatus getFirstByServiceReferenceId(String serviceReferenceId);

    /**
     * Returns the most recent non-voided record matching both service_reference_id (Odoo sale_id)
     * and payment_status (already normalized to uppercase), or null if none exists. This is the
     * idempotency check: a duplicate is only a record that matches the same sale_id AND the same
     * status — a status transition for the same sale_id (e.g. PENDING -&gt; PAID) is NOT a duplicate
     * and must be allowed through as a new row.
     */
    OdooBillingPaymentStatus getLatestByServiceReferenceIdAndStatus(String serviceReferenceId, String paymentStatus);
}
