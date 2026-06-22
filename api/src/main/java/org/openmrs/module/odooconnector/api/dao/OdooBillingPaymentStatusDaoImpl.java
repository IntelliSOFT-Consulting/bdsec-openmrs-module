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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository("odooconnector.OdooBillingPaymentStatusDao")
public class OdooBillingPaymentStatusDaoImpl implements OdooBillingPaymentStatusDao {

    protected final Log log = LogFactory.getLog(getClass());

    @Autowired
    private DbSessionFactory sessionFactory;

    private DbSession getSession() {
        return sessionFactory.getCurrentSession();
    }

    @Override
    public OdooBillingPaymentStatus saveOrUpdate(OdooBillingPaymentStatus record) {
        getSession().saveOrUpdate(record);
        return record;
    }

    @Override
    public OdooBillingPaymentStatus getById(Integer id) {
        return (OdooBillingPaymentStatus) getSession()
                .createCriteria(OdooBillingPaymentStatus.class)
                .add(Restrictions.eq("id", id))
                .add(Restrictions.eq("voided", (byte) 0))
                .uniqueResult();
    }

    @Override
    public OdooBillingPaymentStatus getByPatientVisitService(String patientId, Integer visitId, String serviceType) {
        return (OdooBillingPaymentStatus) getSession()
                .createCriteria(OdooBillingPaymentStatus.class)
                .add(Restrictions.eq("patientId", patientId))
                .add(Restrictions.eq("visitId", visitId))
                .add(Restrictions.eq("serviceType", serviceType))
                .add(Restrictions.eq("voided", (byte) 0))
                .addOrder(Order.desc("odooSyncTimestamp"))
                .setMaxResults(1)
                .uniqueResult();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<OdooBillingPaymentStatus> getAllByVisit(Integer visitId) {
        return getSession()
                .createCriteria(OdooBillingPaymentStatus.class)
                .add(Restrictions.eq("visitId", visitId))
                .add(Restrictions.eq("voided", (byte) 0))
                .addOrder(Order.asc("serviceType"))
                .list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<OdooBillingPaymentStatus> getPendingByVisit(Integer visitId) {
        return getSession()
                .createCriteria(OdooBillingPaymentStatus.class)
                .add(Restrictions.eq("visitId", visitId))
                .add(Restrictions.eq("paymentStatus", "PENDING"))
                .add(Restrictions.eq("voided", (byte) 0))
                .addOrder(Order.asc("serviceType"))
                .list();
    }

    @Override
    public OdooBillingPaymentStatus getFirstByServiceReferenceId(String serviceReferenceId) {
        return (OdooBillingPaymentStatus) getSession()
                .createCriteria(OdooBillingPaymentStatus.class)
                .add(Restrictions.eq("serviceReferenceId", serviceReferenceId))
                .add(Restrictions.eq("voided", (byte) 0))
                .addOrder(Order.asc("id"))
                .setMaxResults(1)
                .uniqueResult();
    }

    @Override
    public OdooBillingPaymentStatus getLatestByServiceReferenceIdAndStatus(String serviceReferenceId, String paymentStatus) {
        return (OdooBillingPaymentStatus) getSession()
                .createCriteria(OdooBillingPaymentStatus.class)
                .add(Restrictions.eq("serviceReferenceId", serviceReferenceId))
                .add(Restrictions.eq("paymentStatus", paymentStatus))
                .add(Restrictions.eq("voided", (byte) 0))
                .addOrder(Order.desc("id"))
                .setMaxResults(1)
                .uniqueResult();
    }

    @Override
    public OdooBillingPaymentStatus getLatestByBedId(Integer bedId) {
        return (OdooBillingPaymentStatus) getSession()
                .createCriteria(OdooBillingPaymentStatus.class)
                .add(Restrictions.eq("bedId", bedId))
                .add(Restrictions.eq("voided", (byte) 0))
                .addOrder(Order.desc("id"))
                .setMaxResults(1)
                .uniqueResult();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<OdooBillingPaymentStatus> getAllBedServiceRecords() {
        return getSession()
                .createCriteria(OdooBillingPaymentStatus.class)
                .add(Restrictions.eq("serviceType", "BED"))
                .add(Restrictions.eq("voided", (byte) 0))
                .add(Restrictions.isNotNull("bedId"))
                .addOrder(Order.asc("bedId"))
                .addOrder(Order.desc("id"))
                .list();
    }
}
