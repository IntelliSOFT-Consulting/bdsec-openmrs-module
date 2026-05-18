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
     * Upserts a payment record received from Odoo.
     * If a non-voided record already exists for the (patientId, visitId, serviceType)
     * combination it is updated in-place; otherwise a new row is inserted.
     *
     * @param dto payload pushed by Odoo
     * @return the persisted entity
     */
    @Transactional
    OdooBillingPaymentStatus saveOrUpdatePaymentStatus(OdooBillingPaymentStatusDTO dto) throws APIException;

    /**
     * Returns the current payment record for a given patient/visit/service combination,
     * or null when no record has been synced from Odoo yet.
     */
    @Transactional(readOnly = true)
    OdooBillingPaymentStatus getPaymentStatus(Integer patientId, Integer visitId, String serviceType) throws APIException;

    /**
     * Returns {@code true} when the service has payment_status PAID or WAIVED,
     * {@code false} in all other cases (including when no record exists — fail-safe
     * behaviour keeps the gate closed until Odoo confirms payment).
     */
    @Transactional(readOnly = true)
    boolean isServicePaid(Integer patientId, Integer visitId, String serviceType) throws APIException;

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
}
