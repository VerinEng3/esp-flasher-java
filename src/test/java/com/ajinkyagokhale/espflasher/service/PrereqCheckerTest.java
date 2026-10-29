package com.ajinkyagokhale.espflasher.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrereqCheckerTest {

    private static PrereqChecker.EsptoolInstall install(String location, String version) {
        return new PrereqChecker.EsptoolInstall(List.of(location), location, version, "teste", null);
    }

    @Test
    void comparesVersionsNumerically() {
        assertTrue(PrereqChecker.compareVersions("5.6.0", "4.12.0") > 0);
        assertTrue(PrereqChecker.compareVersions("4.12.0", "4.5.1") > 0);
        assertTrue(PrereqChecker.compareVersions("5.4.0", "5.6.0") < 0);
        assertEquals(0, PrereqChecker.compareVersions("5.6", "5.6.0"));
    }

    @Test
    void unreadableVersionCountsAsZero() {
        assertTrue(PrereqChecker.compareVersions("4.0.0", "?") > 0);
        assertTrue(PrereqChecker.compareVersions(null, "1.0") < 0);
    }

    @Test
    void picksNewestInstall() {
        var python = install("python313", "5.6.0");
        var idf = install("idf", "4.12.0");
        var pio = install("pio", "5.4.0");
        assertEquals(python, PrereqChecker.newest(List.of(idf, pio, python)));
    }

    @Test
    void tieKeepsFirstByPriority() {
        var first = install("primeiro", "5.6.0");
        var second = install("segundo", "5.6.0");
        assertEquals(first, PrereqChecker.newest(List.of(first, second)));
    }

    @Test
    void emptyListHasNoNewest() {
        assertNull(PrereqChecker.newest(List.of()));
    }
}
