package com.nisovin.shopkeepers.nexo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NexoTradeCostsTest {

	private static final String COIN = "coin_golden_empty_anim";

	@Test
	public void taggedCoinAgainstAnIdlessCostIsAllowed() {
		assertTrue(NexoTradeCosts.refusedOnlyForIdlessCost(null, COIN, null, null));
		assertTrue(NexoTradeCosts.refusedOnlyForIdlessCost(null, COIN, null, COIN));
		assertTrue(NexoTradeCosts.refusedOnlyForIdlessCost("a", "a", null, COIN));
	}

	@Test
	public void matchingIdsAreNotNexosRefusal() {
		assertFalse(NexoTradeCosts.refusedOnlyForIdlessCost(null, null, null, null));
		assertFalse(NexoTradeCosts.refusedOnlyForIdlessCost(COIN, COIN, null, null));
	}

	@Test
	public void aCostWithAnIdKeepsItsRule() {
		assertFalse(NexoTradeCosts.refusedOnlyForIdlessCost("a", "b", null, null));
		assertFalse(NexoTradeCosts.refusedOnlyForIdlessCost("a", null, null, null));
		assertFalse(NexoTradeCosts.refusedOnlyForIdlessCost(null, COIN, "a", "b"));
	}
}
