package org.springframework.samples.petclinic.integration.relay;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Helpers shared by the relay tests. Owners are committed so the relay can see their events. */
final class RelayTestSupport {

    private RelayTestSupport() {
    }

    static Owner saveOwnerCommitted(ClinicService clinicService, PlatformTransactionManager transactionManager,
                                    String lastName) {
        Owner owner = new Owner();
        owner.setFirstName("Relay");
        owner.setLastName(lastName);
        owner.setAddress("1 Harbour Road");
        owner.setCity("Sydney");
        owner.setTelephone("5555555555");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> clinicService.saveOwner(owner));
        return owner;
    }

    static Map<String, Object> latestEvent(JdbcTemplate jdbc, Owner owner) {
        return jdbc.queryForList(
                "SELECT * FROM outbox_event WHERE subject = ? ORDER BY id DESC", "owner/" + owner.getId())
            .get(0);
    }
}
