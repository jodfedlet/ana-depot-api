package com.anadepot.infra.customer;

import com.anadepot.core.customer.model.Customer;
import com.anadepot.core.customer.repository.CustomerRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
class CustomerRepositoryAdapter implements CustomerRepository {

	private final CustomerJpaRepository jpa;
	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager em;

	CustomerRepositoryAdapter(CustomerJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public Customer save(Customer c) {
		var e = jpa.findById(c.id()).orElseGet(CustomerEntity::new);
		e.id = c.id();
		e.firstName = c.firstName();
		e.lastName = c.lastName();
		e.dialCode = c.dialCode();
		e.phone = c.phone();
		e.email = c.email();
		e.authUserId = c.authUserId();
		e.createdAt = c.createdAt();
		e.updatedAt = java.time.Instant.now();
		return toDomain(jpa.saveAndFlush(e));
	}

	@Override
	public Optional<Customer> findById(java.util.UUID id) {
		return jpa.findById(id).map(CustomerRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<Meta> meta(java.util.UUID id) {
		return jpa.findById(id).map(e -> new Meta(e.version == null ? 0 : e.version, e.updatedAt));
	}

	@Override
	public com.anadepot.core.shared.PageResult<Customer> findPage(com.anadepot.core.customer.model.CustomerQuery q, com.anadepot.core.shared.PageRequest page) {
		var from = new StringBuilder(" from CustomerEntity c where 1 = 1");
		var params = new java.util.LinkedHashMap<String, Object>();
		if (q.kind() != null) {
			from.append(q.kind() == com.anadepot.core.customer.model.CustomerQuery.Kind.REGISTERED ? " and c.authUserId is not null" : " and c.authUserId is null");
		}
		if (q.createdFrom() != null) {
			from.append(" and c.createdAt >= :from");
			params.put("from", q.createdFrom());
		}
		if (q.createdTo() != null) {
			from.append(" and c.createdAt < :to");
			params.put("to", q.createdTo());
		}
		if (q.text() != null) {
			var text = q.text().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
			var digits = q.text().replaceAll("\\D", "");
			from.append(" and (lower(c.firstName) like :text escape '\\' or lower(c.lastName) like :text escape '\\' or lower(concat(c.firstName, ' ', c.lastName)) like :text escape '\\'")
					.append(" or lower(c.email) like :text escape '\\'");
			params.put("text", "%" + text + "%");
			if (digits.length() >= 4) {
				from.append(" or c.phone like :phone escape '\\'");
				params.put("phone", "%" + digits + "%");
			}
			from.append(")");
		}
		var order = q.sort() == com.anadepot.core.customer.model.CustomerQuery.Sort.NAME ? " order by lower(c.lastName), lower(c.firstName), c.id" : " order by c.createdAt desc, c.id";
		var query = em.createQuery("select c" + from + order, CustomerEntity.class);
		var count = em.createQuery("select count(c)" + from, Long.class);
		params.forEach((k, v) -> {
			query.setParameter(k, v);
			count.setParameter(k, v);
		});
		query.setFirstResult(page.page() * page.size()).setMaxResults(page.size());
		return new com.anadepot.core.shared.PageResult<>(query.getResultList().stream().map(CustomerRepositoryAdapter::toDomain).toList(), page.page(), page.size(), count.getSingleResult());
	}

	@Override
	public Optional<Customer> findGuestByPhone(String dialCode, String normalizedPhone) {
		return jpa.findByDialCodeAndPhoneAndAuthUserIdIsNull(dialCode, normalizedPhone).map(CustomerRepositoryAdapter::toDomain);
	}

	@Override
	public Optional<Customer> findByAuthUserId(java.util.UUID authUserId) {
		return jpa.findByAuthUserId(authUserId).map(CustomerRepositoryAdapter::toDomain);
	}

	private static Customer toDomain(CustomerEntity e) {
		return new Customer(e.id, e.firstName, e.lastName, e.dialCode, e.phone, e.email, e.authUserId, e.createdAt);
	}
}
