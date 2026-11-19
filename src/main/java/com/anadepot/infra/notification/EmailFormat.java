package com.anadepot.infra.notification;

import com.anadepot.core.shared.money.Money;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/** Formatting for e-mails: French and Haitian Creole share number layout; month and day names are written out because the JDK has no Haitian Creole locale. */
final class EmailFormat {

	static final ZoneId STORE_ZONE = ZoneId.of("America/Port-au-Prince");
	private static final String[] MONTHS_FR = { "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre" };
	private static final String[] MONTHS_HT = { "janvye", "fevriye", "mas", "avril", "me", "jen", "jiyè", "out", "septanm", "oktòb", "novanm", "desanm" };
	private static final String[] DAYS_FR = { "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche" };
	private static final String[] DAYS_HT = { "Lendi", "Madi", "Mèkredi", "Jedi", "Vandredi", "Samdi", "Dimanch" };

	private EmailFormat() {
	}

	/** "25 000,00 HTG": the amount with its own ISO code. Never converted, never added to another currency. */
	static String money(Money m) {
		var symbols = new DecimalFormatSymbols(Locale.FRANCE);
		symbols.setGroupingSeparator(' ');
		symbols.setDecimalSeparator(',');
		var f = new DecimalFormat("#,##0.00", symbols);
		return f.format(m.amount()) + " " + m.currency().name();
	}

	static String date(Instant at, String locale) {
		var d = at.atZone(STORE_ZONE);
		var months = "ht".equals(locale) ? MONTHS_HT : MONTHS_FR;
		return d.getDayOfMonth() + " " + months[d.getMonthValue() - 1] + " " + d.getYear();
	}

	/** ISO day number 1 = Monday ... 7 = Sunday, as stored in the pickup schedule. */
	static String day(int isoDay, String locale) {
		var names = "ht".equals(locale) ? DAYS_HT : DAYS_FR;
		return names[Math.floorMod(isoDay - 1, 7)];
	}
}
