package com.baozi.laninjector.payload;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Enumerate string IDs from the resource table, including R8-stripped R classes. */
final class ResourceStringIndex {
    private static volatile List<Integer> stringIds = Collections.emptyList();
    private static volatile List<Integer> menuIds = Collections.emptyList();
    private static volatile List<Integer> arrayIds = Collections.emptyList();
    private static volatile List<Integer> pluralIds = Collections.emptyList();
    private static volatile List<Integer> layoutIds = Collections.emptyList();
    private static boolean started;

    static synchronized void warm(Context context) {
        if (started) return;
        started = true;
        Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            Set<Integer> ids = new HashSet<>();
            Set<Integer> menus = new HashSet<>();
            Set<Integer> arrays = new HashSet<>();
            Set<Integer> plurals = new HashSet<>();
            Set<Integer> layouts = new HashSet<>();
            List<String> paths = new ArrayList<>();
            paths.add(app.getApplicationInfo().sourceDir);
            if (app.getApplicationInfo().splitSourceDirs != null) {
                Collections.addAll(paths, app.getApplicationInfo().splitSourceDirs);
            }
            for (String path : paths) {
                try (ZipFile zip = new ZipFile(path)) {
                    ZipEntry entry = zip.getEntry("resources.arsc");
                    if (entry == null) continue;
                    try (InputStream input = zip.getInputStream(entry);
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                        ByteBuffer table = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                        collect(table, 0, table.limit(), 0, 0, app.getResources(), ids, menus, arrays, plurals, layouts);
                    }
                } catch (Exception e) {
                    Log.w("LanInjector", "Cannot index string resources: " + path, e);
                }
            }
            // Common programmatically created platform dialog labels.
            ids.add(android.R.string.ok);
            ids.add(android.R.string.cancel);
            menuIds = new ArrayList<>(menus);
            arrayIds = new ArrayList<>(arrays);
            pluralIds = new ArrayList<>(plurals);
            layoutIds = new ArrayList<>(layouts);
            stringIds = new ArrayList<>(ids);
            Log.d("LanInjector", "String resource index ready: " + ids.size() + " IDs");
        }, "LanInjector-resource-index");
        worker.setDaemon(true);
        worker.start();
    }

    static List<Integer> layouts() { return layoutIds; }
    static List<Integer> ids() { return stringIds; }
    static List<Integer> menus() { return menuIds; }
    static List<Integer> arrays() { return arrayIds; }
    static List<Integer> plurals() { return pluralIds; }

    private static void collect(ByteBuffer data, int start, int end, int packageId,
                                int typeOffset, Resources resources, Set<Integer> ids, Set<Integer> menus,
                                Set<Integer> arrays, Set<Integer> plurals, Set<Integer> layouts) {
        for (int pos = start; pos <= end - 8; ) {
            int type = data.getShort(pos) & 0xffff;
            int header = data.getShort(pos + 2) & 0xffff;
            int size = data.getInt(pos + 4);
            if (header < 8 || size < header || size > end - pos) return;
            if (type == 0x0002) {
                collect(data, pos + header, pos + size, packageId, typeOffset, resources, ids, menus, arrays, plurals, layouts);
            } else if (type == 0x0200 && header >= 284) {
                int pkg = data.getInt(pos + 8);
                int offset = header >= 288 ? data.getInt(pos + 284) : 0;
                collect(data, pos + header, pos + size, pkg, offset, resources, ids, menus, arrays, plurals, layouts);
            } else if (type == 0x0202 && header >= 16 && packageId > 0 && packageId <= 255) {
                int typeId = (data.get(pos + 8) & 0xff) + typeOffset;
                int count = data.getInt(pos + 12);
                if (typeId > 0 && typeId <= 255 && count >= 0 && count <= 65536
                        && count <= (size - header) / 4) {
                    int prefix = packageId << 24 | typeId << 16;
                    String resourceType = "";
                    for (int i = 0; i < count; i++) {
                        try {
                            resourceType = resources.getResourceTypeName(prefix | i);
                            break;
                        } catch (Resources.NotFoundException ignored) { }
                    }
                    Set<Integer> destination = "string".equals(resourceType) ? ids
                            : "menu".equals(resourceType) ? menus
                            : "array".equals(resourceType) ? arrays
                            : "plurals".equals(resourceType) ? plurals
                            : "layout".equals(resourceType) ? layouts : null;
                    if (destination != null) for (int i = 0; i < count; i++) destination.add(prefix | i);
                }
            }
            pos += size;
        }
    }
}
