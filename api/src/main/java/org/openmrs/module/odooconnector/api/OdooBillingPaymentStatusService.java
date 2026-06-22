/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.odooconnector.api;

import org.openmrs.api.APIException;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatusDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service contract for Odoo billing payment status operations.
 * Accessed via {@code Context.getService(OdooBillingPaymentStatusService.class)}.
 *
 * Supported service types: CONSULTATION, BED, LAB_ORDER, MEDICATION,
 * PROCEDURE, RADIOLOGY, DENTAL
 */
public interface OdooBillingPaymentStatusService extends OpenmrsService {

    /**
     * Records a payment status update received from Odoo as a new, append-only history row.
     * Existing rows for the same (patientId, visitId, serviceType) are never updated or
     * overwritten — every call inserts a new row, so the full payment lifecycle (e.g.
     * PENDING -&gt; PAID) is preserved. Callers are responsible for idempotency (see
     * {@link #getLatestByServiceReferenceIdAndStatus}) before calling this method.
     *
     * @param dto payload pushed by Odoo; patientId must be the Bahmni patient identifier string
     * @return the persisted entity
     */
    @Transactional
    OdooBillingPaymentStatus saveOrUpdatePaymentStatus(OdooBillingPaymentStatusDTO dto) throws APIException;

    /**
     * Returns the most recent payment record for a given patient/visit/service combination,
     * or null when no record has been synced from Odoo yet.
     *
     * @param patientId Bahmni patient identifier string (e.g. "BDSEC200001")
     */
    @Transactional(readOnly = true)
    OdooBillingPaymentStatus getPaymentStatus(String patientId, Integer visitId, String serviceType) throws APIException;

    /**
     * Returns {@code true} when the most recent record for this combination has payment_status
     * PAID or WAIVED, {@code false} in all other cases (including when no record exists —
     * fail-safe behaviour keeps the gate closed until Odoo confirms payment).
     *
     * @param patientId Bahmni patient identifier string (e.g. "BDSEC200001")
     */
    @Transactional(readOnly = true)
    boolean isServicePaid(String patientId, Integer visitId, String serviceType) throws APIException;

    /**
     * Returns all non-voided PENDING records for the given visit.
     * Used by the billing dashboard and gate interceptor to list what is still owed.
     */
    @Transactional(readOnly = true)
    List<OdooBillingPaymentStatus> getPendingServicesForVisit(Integer visitId) throws APIException;

    /**
     * Soft-deletes a payment record.
     *
     * @param id        primary key of the record to void
     * @param reason    mandatory reason string
     * @param voidedBy  user_id of the operator performing the void
     */
    @Transactional
    void voidPaymentRecord(Integer id, String reason, Integer voidedBy) throws APIException;

    /**
     * Returns the first non-voided record whose service_reference_id equals the given value,
     * or null. General-purpose "any record for this sale_id" lookup — not used for idempotency
     * decisions (see {@link #getLatestByServiceReferenceIdAndStatus}).
     */
    @Transactional(readOnly = true)
    OdooBillingPaymentStatus getFirstByServiceReferenceId(String serviceReferenceId) throws APIException;

    /**
     * Returns the most recent non-voided record matching both the Odoo sale_id
     * (service_reference_id) and payment_status, or null if none exists. Used for idempotency:
     * a duplicate push is one with the same sale_id AND the same status — a status transition
     * for the same sale_id is not a duplicate and should be persisted as a new row.
     */
    @Transactional(readOnly = true)
    OdooBillingPaymentStatus getLatestByServiceReferenceIdAndStatus(String serviceReferenceId, String paymentStatus) throws APIException;

    /**
     * Returns the most recent non-voided BED-service record for the given bed_id, or null if the
     * bed has never been reserved. A bed is currently reserved iff this record's payment_status is
     * PENDING, PAID, or WAIVED (not CANCELLED) — used both to block double-booking and to drive the
     * reservation badge shown on the ward bed grid.
     */
    @Transactional(readOnly = true)
    OdooBillingPaymentStatus getLatestByBedId(Integer bedId) throws APIException;

    /**
     * Returns every non-voided BED-service record, ordered so that grouping by bedId and taking
     * the first row per group yields the latest record per bed. Used by BedOrderOdooService to
     * compute ward/room-level reservation indicators.
     */
    @Transactional(readOnly = true)
    List<OdooBillingPaymentStatus> getAllBedServiceRecords() throws APIException;
}
