/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.odooconnector;

import org.openmrs.BaseOpenmrsObject;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

/**
 * Hibernate entity mapping for the odoo_billing_payment_status table.
 * Each row is an append-only history entry for a patient/visit/service payment
 * status pushed from Odoo — rows are never updated in place, so a status
 * transition (e.g. PENDING -&gt; PAID) always inserts a new row rather than
 * overwriting the old one. The most recent non-voided row for a given
 * (patientId, visitId, serviceType) is what the payment gate checks.
 */
@Entity(name = "odooconnector.OdooBillingPaymentStatus")
@Table(name = "odoo_billing_payment_status")
public class OdooBillingPaymentStatus extends BaseOpenmrsObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** Bahmni patient identifier string (e.g. "BDSEC200001") — never the internal numeric patient PK. */
    @Column(name = "patient_id", nullable = false, length = 50)
    private String patientId;

    @Column(name = "visit_id", nullable = false)
    private Integer visitId;

    @Column(name = "encounter_id")
    private Integer encounterId;

    @Column(name = "service_type", nullable = false, length = 100)
    private String serviceType;

    @Column(name = "service_reference_id", length = 255)
    private String serviceReferenceId;

    @Column(name = "odoo_invoice_id", length = 255)
    private String odooInvoiceId;

    /** Only populated when serviceType is "BED" — the bedmanagement module's bed_id. */
    @Column(name = "bed_id")
    private Integer bedId;

    /** Only populated when serviceType is "BED" — e.g. "IPD-0001", denormalized for display. */
    @Column(name = "bed_number", length = 50)
    private String bedNumber;

    /** Only populated when serviceType is "BED" — the bedmanagement ward's location UUID. */
    @Column(name = "ward_uuid", length = 38)
    private String wardUuid;

    /** Only populated when serviceType is "BED" — the room name within the ward (e.g. "IPD Ward I"). */
    @Column(name = "room_name", length = 100)
    private String roomName;

    @Column(name = "payment_status", nullable = false, length = 50)
    private String paymentStatus = "PENDING";

    @Column(name = "amount_due", precision = 10, scale = 2)
    private BigDecimal amountDue;

    @Column(name = "amount_paid", precision = 10, scale = 2)
    private BigDecimal amountPaid;

    @Column(name = "currency", length = 10)
    private String currency = "KES";

    @Column(name = "payment_date")
    private Date paymentDate;

    @Column(name = "odoo_sync_timestamp", nullable = false)
    private Date odooSyncTimestamp;

    @Column(name = "created_at", nullable = false)
    private Date createdAt;

    @Column(name = "updated_at", nullable = false)
    private Date updatedAt;

    @Column(name = "voided", nullable = false)
    private byte voided = 0;

    @Column(name = "voided_by")
    private Integer voidedBy;

    @Column(name = "void_reason", length = 255)
    private String voidReason;

    // uuid is inherited from BaseOpenmrsObject and mapped to the 'uuid' column
    // by OpenMRS's @MappedSuperclass. No redeclaration needed here.

    @PrePersist
    protected void onCreate() {
        Date now = new Date();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (getUuid() == null) {
            setUuid(UUID.randomUUID().toString());
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Date();
    }

    @Override
    public Integer getId() {
        return id;
    }

    @Override
    public void setId(Integer id) {
        this.id = id;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public Integer getVisitId() {
        return visitId;
    }

    public void setVisitId(Integer visitId) {
        this.visitId = visitId;
    }

    public Integer getEncounterId() {
        return encounterId;
    }

    public void setEncounterId(Integer encounterId) {
        this.encounterId = encounterId;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getServiceReferenceId() {
        return serviceReferenceId;
    }

    public void setServiceReferenceId(String serviceReferenceId) {
        this.serviceReferenceId = serviceReferenceId;
    }

    public String getOdooInvoiceId() {
        return odooInvoiceId;
    }

    public void setOdooInvoiceId(String odooInvoiceId) {
        this.odooInvoiceId = odooInvoiceId;
    }

    public Integer getBedId() {
        return bedId;
    }

    public void setBedId(Integer bedId) {
        this.bedId = bedId;
    }

    public String getBedNumber() {
        return bedNumber;
    }

    public void setBedNumber(String bedNumber) {
        this.bedNumber = bedNumber;
    }

    public String getWardUuid() {
        return wardUuid;
    }

    public void setWardUuid(String wardUuid) {
        this.wardUuid = wardUuid;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public BigDecimal getAmountDue() {
        return amountDue;
    }

    public void setAmountDue(BigDecimal amountDue) {
        this.amountDue = amountDue;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public void setAmountPaid(BigDecimal amountPaid) {
        this.amountPaid = amountPaid;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Date getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(Date paymentDate) {
        this.paymentDate = paymentDate;
    }

    public Date getOdooSyncTimestamp() {
        return odooSyncTimestamp;
    }

    public void setOdooSyncTimestamp(Date odooSyncTimestamp) {
        this.odooSyncTimestamp = odooSyncTimestamp;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public byte getVoided() {
        return voided;
    }

    public void setVoided(byte voided) {
        this.voided = voided;
    }

    public Integer getVoidedBy() {
        return voidedBy;
    }

    public void setVoidedBy(Integer voidedBy) {
        this.voidedBy = voidedBy;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }
}
