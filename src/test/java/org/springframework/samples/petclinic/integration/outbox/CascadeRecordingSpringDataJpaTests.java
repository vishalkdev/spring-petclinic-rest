package org.springframework.samples.petclinic.integration.outbox;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"spring-data-jpa", "hsqldb"})
class CascadeRecordingSpringDataJpaTests extends AbstractCascadeRecordingTests {
}
