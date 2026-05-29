/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.odooconnector.api.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.APIException;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatusDTO;
import org.openmrs.module.odooconnector.api.OdooBillingPaymentStatusService;
import org.openmrs.module.odooconnector.api.dao.OdooBillingPaymentStatusDao;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class OdooBillingPaymentStatusServiceImpl
        extends BaseOpenmrsService
        implements OdooBillingPaymentStatusService {

    private static final Log log = LogFactory.getLog(OdooBillingPaymentStatusServiceImpl.class);

    private static final List<String> PAID_STATUSES = Arrays.asList("PAID", "WAIVED");

    private OdooBillingPaymentStatusDao dao;

    /** Injected via moduleApplicationContext.xml */
    public void setDao(OdooBillingPaymentStatusDao dao) {
        this.dao = dao;
    }

    @Override
    public OdooBillingPaymentStatus saveOrUpdatePaymentStatus(OdooBillingPaymentStatusDTO dto) throws APIException {
        log.info("[OdooBilling] Record received from Odoo — patient=" + dto.getPatientId()
                + " visit=" + dto.getVisitId()
                + " service=" + dto.getServiceType()
                + " status=" + dto.getPaymentStatus()
                + " invoice=" + dto.getOdooInvoiceId());

        OdooBillingPaymentStatus existing = dao.getByPatientVisitService(
                dto.getPatientId(), dto.getVisitId(), dto.getServiceType());

        OdooBillingPaymentStatus record = (existing != null) ? existing : new OdooBillingPaymentStatus();

        record.setPatientId(dto.getPatientId());
        record.setVisitId(dto.getVisitId());
        record.setEncounterId(dto.getEncounterId());
        record.setServiceType(dto.getServiceType());
        record.setServiceReferenceId(dto.getServiceReferenceId());
        record.setOdooInvoiceId(dto.getOdooInvoiceId());
        record.setPaymentStatus(dto.getPaymentStatus() != null ? dto.getPaymentStatus() : "PENDING");
        record.setAmountDue(dto.getAmountDue());
        record.setAmountPaid(dto.getAmountPaid());
        record.setCurrency(dto.getCurrency() != null ? dto.getCurrency() : "KES");
        record.setPaymentDate(dto.getPaymentDate());
        record.setOdooSyncTimestamp(dto.getOdooSyncTimestamp() != null ? dto.getOdooSyncTimestamp() : new Date());

        if (record.getUuid() == null) {
            record.setUuid(dto.getUuid() != null ? dto.getUuid() : UUID.randomUUID().toString());
        }

        OdooBillingPaymentStatus saved = dao.saveOrUpdate(record);

        log.info("[OdooBilling] Upsert complete — id=" + saved.getId()
                + " uuid=" + saved.getUuid()
                + " status=" + saved.getPaymentStatus()
                + " action=" + (existing != null ? "UPDATE" : "INSERT"));

        return saved;
    }

    @Override
    public OdooBillingPaymentStatus getPaymentStatus(Integer patientId, Integer visitId, String serviceType)
            throws APIException {
        return dao.getByPatientVisitService(patientId, visitId, serviceType);
    }

    @Override
    public boolean isServicePaid(Integer patientId, Integer visitId, String serviceType) throws APIException {
        log.debug("[OdooBilling] Payment gate check — patient=" + patientId
                + " visit=" + visitId
                + " service=" + serviceType);

        OdooBillingPaymentStatus record = dao.getByPatientVisitService(patientId, visitId, serviceType);

        boolean paid = record != null && PAID_STATUSES.contains(record.getPaymentStatus());

        log.info("[OdooBilling] Payment gate result — patient=" + patientId
                + " visit=" + visitId
                + " service=" + serviceType
                + " paid=" + paid
                + (record != null ? " status=" + record.getPaymentStatus() : " (no record)"));

        return paid;
    }

    @Override
    public List<OdooBillingPaymentStatus> getPendingServicesForVisit(Integer visitId) throws APIException {
        log.debug("[OdooBilling] Fetching pending services for visit=" + visitId);
        return dao.getPendingByVisit(visitId);
    }

    @Override
    public OdooBillingPaymentStatus getFirstByServiceReferenceId(String serviceReferenceId) throws APIException {
        return dao.getFirstByServiceReferenceId(serviceReferenceId);
    }

    @Override
    public void voidPaymentRecord(Integer id, String reason, Integer voidedBy) throws APIException {
        if (reason == null || reason.trim().isEmpty()) {
            throw new APIException("void reason is required");
        }

        OdooBillingPaymentStatus record = dao.getById(id);
        if (record == null) {
            throw new APIException("No payment record found with id=" + id);
        }

        record.setVoided((byte) 1);
        record.setVoidedBy(voidedBy);
        record.setVoidReason(reason);
        dao.saveOrUpdate(record);

        log.info("[OdooBilling] Record voided — id=" + id
                + " reason='" + reason + "'"
                + " voidedBy=" + voidedBy);
    }
}
