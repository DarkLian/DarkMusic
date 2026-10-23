package com.darkmusic.client.cleanup;

import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateGroupsTest {
    Track track(String id) {
        return new Track("root", id, Path.of(id), id, "", "", 100, 1, 1, "");
    }

    DuplicateResult pair(String a, String b) {
        return new DuplicateResult(DuplicateResult.pair(a, b), track(a), track(b), a, b, Set.of(DuplicateResult.Evidence.FILENAME), DuplicateResult.Strength.POSSIBLE);
    }

    @Test
    void fiveMatchingFilesBecomeOneGroupWithTenActualEdges() {
        var pairs = new ArrayList<DuplicateResult>();
        for (int i = 0; i < 5; i++) for (int j = i + 1; j < 5; j++) pairs.add(pair("f" + i, "f" + j));
        var groups = DuplicateGroups.build(pairs);
        assertEquals(1, groups.size());
        assertEquals(5, groups.getFirst().tracks().size());
        assertEquals(pairs, groups.getFirst().pairs());
        assertThrows(UnsupportedOperationException.class, () -> groups.getFirst().pairs().clear());
    }

    @Test
    void chainsKeepEvidenceWithoutInventingTransitiveMatches() {
        var ab = pair("a", "b");
        var bc = pair("b", "c");
        var de = pair("d", "e");
        var groups = DuplicateGroups.build(List.of(ab, bc, de));
        assertEquals(2, groups.size());
        assertEquals(List.of(ab, bc), groups.getFirst().pairs());
        assertEquals(3, groups.getFirst().tracks().size());
        var reverse = DuplicateGroups.build(List.of(bc, ab));
        assertEquals(groups.getFirst().id(), reverse.getFirst().id());
        assertEquals(2, DuplicateGroups.build(List.of(ab, de)).size());
    }

    @Test
    void longChainsAreGroupedWithoutRecursionOrPairEnumeration() {
        var pairs = new ArrayList<DuplicateResult>();
        for (int i = 20000; i > 0; i--) pairs.add(pair("f" + i, "f" + (i - 1)));
        var groups = DuplicateGroups.build(pairs);
        assertEquals(1, groups.size());
        assertEquals(20001, groups.getFirst().tracks().size());
        assertEquals(20000, groups.getFirst().pairs().size());
    }
}
