package org.springframework.samples.petclinic.integration.outbox;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"jpa", "hsqldb"})
class CascadeRecordingJpaTests extends AbstractCascadeRecordingTests {
}
