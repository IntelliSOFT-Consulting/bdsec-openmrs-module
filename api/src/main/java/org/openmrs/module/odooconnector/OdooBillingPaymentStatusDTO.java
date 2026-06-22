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

import java.math.BigDecimal;
import java.util.Date;

/**
 * REST payload DTO for the odoo_billing_payment_status resource. Odoo pushes this payload; Bahmni
 * reads it for gate checks. Supported service types: CONSULTATION, BED, LAB_ORDER, MEDICATION,
 * PROCEDURE, RADIOLOGY, DENTAL Supported payment statuses: PENDING, PAID, WAIVED, CANCELLED
 */
public class OdooBillingPaymentStatusDTO {
	
	/**
	 * Bahmni patient identifier string (e.g. "BDSEC200001") — never the internal numeric patient
	 * PK.
	 */
	private String patientId;
	
	private Integer visitId;
	
	private Integer encounterId;
	
	private String serviceType;
	
	private String serviceReferenceId;
	
	private String odooInvoiceId;
	
	private String paymentStatus;
	
	private BigDecimal amountDue;
	
	private BigDecimal amountPaid;
	
	private String currency;
	
	private Date paymentDate;
	
	private Date odooSyncTimestamp;
	
	private String uuid;
	
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
	
	public String getUuid() {
		return uuid;
	}
	
	public void setUuid(String uuid) {
		this.uuid = uuid;
	}
}
