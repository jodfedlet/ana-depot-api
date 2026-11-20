package com.anadepot.infra.notification;

import com.anadepot.core.notification.model.NotificationType;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.messageresolver.SpringMessageResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders a customer e-mail: an HTML part and a plain-text part from the SAME view, in French or Haitian Creole. Its own Thymeleaf engine (no MVC view resolution), UTF-8 end to end,
 * templates only escape (th:text): untrusted text (customer names, product names) can never become markup.
 */
class EmailRenderer {

	record Rendered(String subject, String html, String text) {
	}

	private final SpringTemplateEngine engine;
	private final MessageSource messages;

	EmailRenderer() {
		var bundle = new ResourceBundleMessageSource();
		bundle.setBasename("email/messages");
		bundle.setDefaultEncoding("UTF-8");
		bundle.setFallbackToSystemLocale(false);
		this.messages = bundle;
		var html = new ClassLoaderTemplateResolver();
		html.setPrefix("templates/email/");
		html.setSuffix(".html");
		html.setTemplateMode(TemplateMode.HTML);
		html.setCharacterEncoding("UTF-8");
		html.setResolvablePatterns(Set.of("html/*"));
		html.setCacheable(true);
		html.setOrder(1);
		var text = new ClassLoaderTemplateResolver();
		text.setPrefix("templates/email/");
		text.setSuffix(".txt");
		text.setTemplateMode(TemplateMode.TEXT);
		text.setCharacterEncoding("UTF-8");
		text.setResolvablePatterns(Set.of("text/*"));
		text.setCacheable(true);
		text.setOrder(2);
		this.engine = new SpringTemplateEngine();
		engine.addTemplateResolver(html);
		engine.addTemplateResolver(text);
		var resolver = new SpringMessageResolver();
		resolver.setMessageSource(bundle);
		engine.setMessageResolver(resolver);
	}

	Rendered render(NotificationType type, EmailView view) {
		var locale = Locale.forLanguageTag("ht".equals(view.locale()) ? "ht" : "fr");
		var ctx = new Context(locale);
		ctx.setVariable("v", view);
		var key = key(type);
		var subject = messages.getMessage("email." + key + ".subject", new Object[] { view.reference() }, locale);
		var html = "<!DOCTYPE html>\n" + engine.process("html/" + type.template(), ctx);
		var text = engine.process("text/" + type.template(), ctx);
		return new Rendered(subject, html, text.strip() + "\n");
	}

	/** The message-catalog prefix of a type (kept next to the templates: one place to look when adding an event). */
	static String key(NotificationType type) {
		return switch (type) {
			case RESERVATION_SUBMITTED -> "submitted";
			case RESERVATION_CONFIRMED -> "confirmed";
			case RESERVATION_REJECTED -> "rejected";
			case RESERVATION_READY_FOR_PICKUP -> "ready";
			case RESERVATION_COLLECTED -> "collected";
			case RESERVATION_CANCELLED -> "cancelled";
			case RESERVATION_EXPIRED -> "expired";
			case RESERVATION_EXPIRING -> "expiring";
			case RESERVATION_CANCELLED_BY_CUSTOMER -> "cancelledByCustomer";
			case PAYMENT_RECEIVED -> "paymentReceived";
			case PAYMENT_FAILED -> "paymentFailed";
			case CHECKOUT_EXPIRED -> "checkoutExpired";
		};
	}
}
