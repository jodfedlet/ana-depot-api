package com.anadepot.infra.storage;

import com.anadepot.core.catalog.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically expires abandoned uploads and deletes their objects (an unfinished upload never leaves a file in the bucket for long). Safe on every instance. */
@Component
class UploadCleanupScheduler {

	private static final Logger log = LoggerFactory.getLogger(UploadCleanupScheduler.class);

	private final UploadService uploads;

	UploadCleanupScheduler(UploadService uploads) {
		this.uploads = uploads;
	}

	@Scheduled(initialDelayString = "PT2M", fixedDelayString = "${ana.upload.cleanup-interval:PT15M}")
	void run() {
		try {
			int expired = uploads.expireAbandoned();
			if (expired > 0) {
				log.info("Expired {} abandoned upload(s)", expired);
			}
		} catch (RuntimeException e) {
			log.warn("Abandoned-upload cleanup failed ({}); it is retried at the next interval", e.getClass().getSimpleName());
		}
	}
}
