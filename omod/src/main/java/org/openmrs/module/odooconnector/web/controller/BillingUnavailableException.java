package org.openmrs.module.odooconnector.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE, reason = "Billing system unavailable. Contact support.")
public class BillingUnavailableException extends RuntimeException {

	public BillingUnavailableException(String message) {
		super(message);
	}
}
