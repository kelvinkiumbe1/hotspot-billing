package com.spalimited.hotspotbilling;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the whole application wires up: every bean, every controller, every
 * scheduled job. Cheap to run and catches the class of mistake that otherwise
 * only shows up as a failed deploy — a missing dependency, a circular one, a
 * property with no default.
 */
@SpringBootTest
@ActiveProfiles("test")
class HotspotBillingApplicationTests {

	@Test
	void contextLoads() {
	}

}
