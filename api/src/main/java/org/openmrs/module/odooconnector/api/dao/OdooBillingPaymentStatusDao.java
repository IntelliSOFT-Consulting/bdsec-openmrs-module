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
     * Returns the non-voided record matching (patientId, visitId, serviceType),
     * or null if none exists.
     */
    OdooBillingPaymentStatus getByPatientVisitService(Integer patientId, Integer visitId, String serviceType);

    /**
     * Returns all non-voided records for the given visit, regardless of status.
     */
    List<OdooBillingPaymentStatus> getAllByVisit(Integer visitId);

    /**
     * Returns non-voided records with payment_status = 'PENDING' for the visit.
     */
    List<OdooBillingPaymentStatus> getPendingByVisit(Integer visitId);
}
