package com.anadepot.infra.storage;

import static com.anadepot.testsupport.TestData.htg;
import static com.anadepot.testsupport.TestData.price;
import static com.anadepot.testsupport.TestData.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anadepot.core.catalog.model.ProductStatus;
import com.anadepot.core.catalog.model.UploadStatus;
import com.anadepot.core.catalog.service.UploadService;
import com.anadepot.infra.AbstractIntegrationTest;
import com.anadepot.testsupport.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** With a zero-length upload window, intents are immediately abandoned: expiry and cleanup behaviour. */
@TestPropertySource(properties = {"ana.upload.url-ttl=PT0S", "ana.upload.abandoned-after=PT0S"})
@Import(UploadLifecycleIntegrationTest.FakeStorageConfig.class)
class UploadCleanupIntegrationTest extends AbstractIntegrationTest {

	@Autowired UploadService uploads;
	@Autowired UploadLifecycleIntegrationTest.FakeObjectStorage storage;
	@Autowired UploadIntentJpaRepository intentJpa;

	@Test
	void anExpiredPresignedUrlCannotBeCompleted_andAbandonedIntentsAreCleanedUpIdempotently() throws Exception {
		var category = TestData.category().category();
		catalog.saveCategory(category);
		var p = catalog.saveProduct(product(category.id(), ProductStatus.ACTIVE, price(htg("10.00"), null, 1)), staff);
		var ticket = uploads.requestProductImage(p.id(), "image/png", 100, staff, key());
		storage.clientUploads(ticket.intent().objectKey(), "image/png", UploadLifecycleIntegrationTest.PNG, 100);
		Thread.sleep(20);

		assertThatThrownBy(() -> uploads.completeProductImage(ticket.intent().id(), staff)).extracting("code").isEqualTo("upload_expired");

		int first = uploads.expireAbandoned();
		assertThat(first).isGreaterThanOrEqualTo(1);
		assertThat(intentJpa.findById(ticket.intent().id()).orElseThrow().status).isEqualTo(UploadStatus.EXPIRED);
		assertThat(storage.objects).doesNotContainKey(ticket.intent().objectKey());            // the orphaned object is removed
		assertThat(uploads.expireAbandoned()).isZero();                                        // running it again changes nothing
		assertThat(catalog.getProduct(p.id()).images()).isEmpty();
	}
}
