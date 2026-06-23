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
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatusDTO;
import org.openmrs.module.odooconnector.api.OdooBillingPaymentStatusService;
import org.openmrs.module.odooconnector.api.dao.OdooBillingPaymentStatusDao;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        // Append-only: always insert a new row so the full payment lifecycle (e.g. PENDING ->
        // PAID) is preserved as history. Never look up and update an existing row in place.
        OdooBillingPaymentStatus record = new OdooBillingPaymentStatus();

        record.setPatientId(dto.getPatientId());
        record.setVisitId(dto.getVisitId());
        record.setEncounterId(dto.getEncounterId());
        record.setServiceType(dto.getServiceType());
        record.setServiceReferenceId(dto.getServiceReferenceId());
        record.setOdooInvoiceId(dto.getOdooInvoiceId());
        record.setBedId(dto.getBedId());
        record.setBedNumber(dto.getBedNumber());
        record.setWardUuid(dto.getWardUuid());
        record.setRoomName(dto.getRoomName());
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

        log.info("[OdooBilling] History row inserted — id=" + saved.getId()
                + " uuid=" + saved.getUuid()
                + " status=" + saved.getPaymentStatus());

        return saved;
    }

    @Override
    public OdooBillingPaymentStatus getPaymentStatus(String patientId, Integer visitId, String serviceType)
            throws APIException {
        OdooBillingPaymentStatus record = dao.getByPatientVisitService(patientId, visitId, serviceType);
        if (record != null) {
            return record;
        }
        return getPaymentStatusFromLinkedPriorVisit(patientId, visitId, serviceType);
    }

    @Override
    public boolean isServicePaid(String patientId, Integer visitId, String serviceType) throws APIException {
        log.debug("[OdooBilling] Payment gate check — patient=" + patientId
                + " visit=" + visitId
                + " service=" + serviceType);

        OdooBillingPaymentStatus record = getPaymentStatus(patientId, visitId, serviceType);

        boolean paid = record != null && PAID_STATUSES.contains(record.getPaymentStatus());

        log.info("[OdooBilling] Payment gate result — patient=" + patientId
                + " visit=" + visitId
                + " service=" + serviceType
                + " paid=" + paid
                + (record != null ? " status=" + record.getPaymentStatus() : " (no record)"));

        return paid;
    }

    /**
     * A visit-type switch during one continuous admission (e.g. OPD -&gt; IPD, via Bahmni's
     * "close current visit and start a new one") closes the active visit and immediately opens a
     * new one for the same patient — the new visit's start time equals the old visit's stop time.
     * From a billing standpoint that's the same episode, not a new one: a service already paid
     * under the closed visit must still count as paid on the new visit, otherwise every payment
     * gate (dashboard access, admit button, duplicate-charge check) incorrectly re-blocks a patient
     * who already paid, the moment their visit type changes.
     *
     * Walks back through any chain of such immediately-consecutive visits (capped to avoid
     * pathological loops) looking for a non-voided record of the given service type.
     */
    private OdooBillingPaymentStatus getPaymentStatusFromLinkedPriorVisit(String patientId, Integer visitId,
            String serviceType) {
        Visit visit = Context.getVisitService().getVisit(visitId);
        if (visit == null || visit.getPatient() == null || visit.getStartDatetime() == null) {
            return null;
        }

        Set<Integer> visited = new HashSet<>();
        visited.add(visitId);
        Patient patient = visit.getPatient();
        Date boundary = visit.getStartDatetime();

        for (int hop = 0; hop < 10; hop++) {
            Visit priorVisit = findVisitEndingAt(patient, boundary, visited);
            if (priorVisit == null) {
                return null;
            }
            visited.add(priorVisit.getVisitId());

            OdooBillingPaymentStatus record =
                    dao.getByPatientVisitService(patientId, priorVisit.getVisitId(), serviceType);
            if (record != null) {
                log.info("[OdooBilling] Resolved " + serviceType + " record via linked prior visit="
                        + priorVisit.getVisitId() + " (visit-type switch chain) for current visit=" + visitId
                        + " patient=" + patientId);
                return record;
            }
            if (priorVisit.getStartDatetime() == null) {
                return null;
            }
            boundary = priorVisit.getStartDatetime();
        }
        return null;
    }

    /**
     * Finds the patient's visit whose stop time is (within a small tolerance of) the given
     * boundary instant — i.e. the visit that was closed at the exact moment the next one in the
     * chain was opened. Excludes visit ids already walked, to bound the search to a strictly
     * backward-moving chain.
     */
    private Visit findVisitEndingAt(Patient patient, Date boundary, Set<Integer> exclude) {
        List<Visit> visits = Context.getVisitService().getVisitsByPatient(patient);
        Visit best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Visit v : visits) {
            if (exclude.contains(v.getVisitId()) || v.getStopDatetime() == null) {
                continue;
            }
            long diff = Math.abs(v.getStopDatetime().getTime() - boundary.getTime());
            if (diff <= 5000 && diff < bestDiff) {
                best = v;
                bestDiff = diff;
            }
        }
        return best;
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
    public OdooBillingPaymentStatus getLatestByServiceReferenceIdAndStatus(String serviceReferenceId, String paymentStatus)
            throws APIException {
        return dao.getLatestByServiceReferenceIdAndStatus(serviceReferenceId, paymentStatus);
    }

    @Override
    public OdooBillingPaymentStatus getLatestByBedId(Integer bedId) throws APIException {
        return dao.getLatestByBedId(bedId);
    }

    @Override
    public List<OdooBillingPaymentStatus> getAllBedServiceRecords() throws APIException {
        return dao.getAllBedServiceRecords();
    }

    @Override
    public OdooBillingPaymentStatus getLatestBedReservationForPatient(String patientId) throws APIException {
        return dao.getLatestBedReservationForPatient(patientId);
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
