package com.anadepot.core.settings.model;

import com.anadepot.core.shared.LocalizedText;
import java.util.List;

/** Where customers collect. No value is ever invented: every field starts empty until the owner enters it. */
public record PickupLocation(String storeName, String addressLine, String city, String region, String country, String postalCode,
		String phone, String email, LocalizedText additionalLocationInfo, List<DaySchedule> schedule, LocalizedText closureInfo) {

	public PickupLocation {
		storeName = nullToEmpty(storeName);
		addressLine = nullToEmpty(addressLine);
		city = nullToEmpty(city);
		region = nullToEmpty(region);
		country = nullToEmpty(country);
		postalCode = postalCode == null || postalCode.isBlank() ? null : postalCode.strip();
		phone = nullToEmpty(phone);
		email = email == null || email.isBlank() ? null : email.strip();
		additionalLocationInfo = additionalLocationInfo == null ? LocalizedText.empty() : additionalLocationInfo;
		schedule = schedule == null ? List.of() : List.copyOf(schedule);
		closureInfo = closureInfo == null ? LocalizedText.empty() : closureInfo;
	}

	public static PickupLocation empty() {
		return new PickupLocation("", "", "", "", "", null, "", null, LocalizedText.empty(), List.of(), LocalizedText.empty());
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s.strip();
	}
}
