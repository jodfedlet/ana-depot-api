package com.anadepot.infra.customer;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class CustomerEntity {

	@Id
	UUID id;
	String firstName;
	String lastName;
	String dialCode;
	String phone;
	String email;
	UUID authUserId;
	Instant createdAt;
	Instant updatedAt;
	@jakarta.persistence.Version
	Long version;

	protected CustomerEntity() {
	}

	public UUID id() {
		return id;
	}
}
