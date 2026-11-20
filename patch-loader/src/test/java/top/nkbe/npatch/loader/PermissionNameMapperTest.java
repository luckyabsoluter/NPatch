package top.nkbe.npatch.loader;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class PermissionNameMapperTest {
    @Test public void requestMappingPreservesUnknownEntriesAndTheOriginalArray() {
        String old = "example.vendor.permission.PRIVATE";
        String isolated = "npatch.p0123";
        String external = "android.permission.INTERNET";
        String[] input = {old, external, null, old};
        Map<String, String> mapping = Map.of(old, isolated);
        String[] mapped = PermissionNameMapper.mapNames(input, name -> mapping.getOrDefault(name, name));
        assertArrayEquals(new String[]{isolated, external, null, isolated}, mapped);
        assertArrayEquals(new String[]{old, external, null, old}, input);
        assertArrayEquals(input, PermissionNameMapper.mapNames(mapped, name -> isolated.equals(name) ? old : name));
        assertSame(mapped, PermissionNameMapper.mapNames(mapped, name -> mapping.getOrDefault(name, name)));
        assertNull(PermissionNameMapper.mapNames(null, name -> name));
    }
}
