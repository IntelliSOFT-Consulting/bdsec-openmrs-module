/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.odooconnector.exception;

/**
 * Thrown by {@code BillingPaymentGateInterceptor} when a patient attempts to access a clinical
 * service that has not been paid or waived in Odoo. The interceptor writes HTTP 402 directly to the
 * response before throwing, so no Spring MVC @ResponseStatus annotation is needed here.
 */
public class PaymentRequiredException extends RuntimeException {
	
	private final Integer patientId;
	
	private final Integer visitId;
	
	private final String serviceType;
	
	public PaymentRequiredException(Integer patientId, Integer visitId, String serviceType) {
		super("Payment required for service " + serviceType + " — patient=" + patientId + " visit=" + visitId);
		this.patientId = patientId;
		this.visitId = visitId;
		this.serviceType = serviceType;
	}
	
	public Integer getPatientId() {
		return patientId;
	}
	
	public Integer getVisitId() {
		return visitId;
	}
	
	public String getServiceType() {
		return serviceType;
	}
}
